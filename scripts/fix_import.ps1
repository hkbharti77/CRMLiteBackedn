$psql       = "C:\Program Files\PostgreSQL\18\bin\psql.exe"
$remoteConn = "postgresql://crmlite_user:6gruN8FyhnKFApb2r2pYFBgkB8B86CJE@dpg-d91gebjtqb8s7390bh9g-a.ohio-postgres.render.com:5432/crmlite"
$localConn  = "postgresql://root:root@localhost:5433/chatcrmdb"
$TENANT_ID  = "9431bdc0-7a42-4f5e-a86a-fc2b174f08bf"
$USER_ID    = "f2743829-9058-4a1e-aaf0-1a536e6508b9"

function Pipe-Table {
    param([string]$Table, [string]$LocalWhere)
    $localCols  = ([string](& $psql $localConn  -t -A -c "SELECT string_agg(column_name, ',' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema='public' AND table_name='$Table'")).Trim() -split ','
    $remoteCols = ([string](& $psql $remoteConn -t -A -c "SELECT string_agg(column_name, ',' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema='public' AND table_name='$Table' AND is_generated='NEVER'")).Trim() -split ','
    $common  = $localCols | Where-Object { $_ -ne "" -and $remoteCols -contains $_ }
    if ($common.Count -eq 0) { Write-Host "  ${Table}: no common cols" -ForegroundColor Yellow; return }
    $colList   = $common -join ','
    $selectStr = ($common | ForEach-Object { "`"$_`"" }) -join ','
    Write-Host "  ${Table} ($($common.Count) cols, excl: $($localCols.Count - $common.Count))..." -NoNewline
    & $psql $localConn -c "COPY (SELECT $selectStr FROM `"$Table`" WHERE $LocalWhere) TO STDOUT WITH CSV HEADER" | & $psql $remoteConn -c "COPY `"$Table`" ($colList) FROM STDIN WITH CSV HEADER"
    Write-Host " done" -ForegroundColor Green
}

Write-Host ""
Write-Host "=== Fix 1: app_users ===" -ForegroundColor Cyan

$oldId = ([string](& $psql $remoteConn -t -A -c "SELECT id FROM app_users WHERE email = 'hkbharti77@gmail.com'")).Trim()
Write-Host "  Remote UUID: '$oldId'  |  Local UUID: '$USER_ID'"

if ($oldId -ne "" -and $oldId -ne $USER_ID) {
    Write-Host "  UUID mismatch - discovering all FK references to old UUID..."

    # Dynamically find every table/column that has an FK to app_users.id
    $fkQuery = "SELECT kcu.table_name, kcu.column_name FROM information_schema.table_constraints tc JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name JOIN information_schema.referential_constraints rc ON tc.constraint_name = rc.constraint_name JOIN information_schema.key_column_usage ccu ON rc.unique_constraint_name = ccu.constraint_name WHERE tc.constraint_type = 'FOREIGN KEY' AND ccu.table_name = 'app_users' AND ccu.column_name = 'id' ORDER BY kcu.table_name"
    $fkRows = & $psql $remoteConn -t -A -F "|" -c $fkQuery

    foreach ($row in $fkRows) {
        $row = $row.Trim()
        if ($row -eq "") { continue }
        $parts  = $row -split '\|'
        $fkTbl  = $parts[0]
        $fkCol  = $parts[1]
        Write-Host "    DELETE FROM $fkTbl WHERE $fkCol = '$oldId'..." -NoNewline
        & $psql $remoteConn -c "DELETE FROM `"$fkTbl`" WHERE `"$fkCol`" = '$oldId';"
        Write-Host " done" -ForegroundColor Gray
    }

    Write-Host "  Deleting old app_users row..." -NoNewline
    & $psql $remoteConn -c "DELETE FROM app_users WHERE id = '$oldId';"
    Write-Host " done" -ForegroundColor Gray

    Pipe-Table "app_users" "id = '$USER_ID'"

} elseif ($oldId -eq $USER_ID) {
    Write-Host "  Correct UUID already present - skipping" -ForegroundColor Green
} else {
    Write-Host "  User not in remote - inserting..."
    Pipe-Table "app_users" "id = '$USER_ID'"
}

Write-Host ""
Write-Host "=== Fix 2: subscription_plans ===" -ForegroundColor Cyan
$planCount = ([string](& $psql $remoteConn -t -A -c "SELECT COUNT(*) FROM subscription_plans WHERE id = 'PRO'")).Trim()
Write-Host "  PRO rows in remote: '$planCount'"
if ($planCount -ne "1") {
    Pipe-Table "subscription_plans" "1=1"
} else {
    Write-Host "  PRO exists - skipping" -ForegroundColor Green
}

Write-Host ""
Write-Host "=== Fix 3: FK-dependent tables (in order) ===" -ForegroundColor Cyan
Pipe-Table "tenant_subscriptions" "tenant_id = '$TENANT_ID'"
Pipe-Table "contacts"             "tenant_id = '$TENANT_ID'"
Pipe-Table "leads"                "tenant_id = '$TENANT_ID'"
Pipe-Table "lead_enquiries"       "lead_id IN (SELECT id FROM leads WHERE tenant_id = '$TENANT_ID')"
Pipe-Table "appointments"         "tenant_id = '$TENANT_ID'"
Pipe-Table "chat_messages"        "tenant_id = '$TENANT_ID'"
Pipe-Table "tickets"              "tenant_id = '$TENANT_ID'"
Pipe-Table "business_services"    "tenant_id = '$TENANT_ID'"
Pipe-Table "activity_logs"        "owner_id = '$USER_ID'"
Pipe-Table "user_sessions"        "user_id = '$USER_ID'"

Write-Host ""
Write-Host "=== Verification ===" -ForegroundColor Cyan
& $psql $remoteConn -c "SELECT 'tenants' AS tbl, COUNT(*) FROM tenants WHERE id='$TENANT_ID' UNION ALL SELECT 'app_users', COUNT(*) FROM app_users WHERE tenant_id='$TENANT_ID' UNION ALL SELECT 'contacts', COUNT(*) FROM contacts WHERE tenant_id='$TENANT_ID' UNION ALL SELECT 'leads', COUNT(*) FROM leads WHERE tenant_id='$TENANT_ID' UNION ALL SELECT 'lead_enquiries', COUNT(*) FROM lead_enquiries le JOIN leads l ON le.lead_id=l.id WHERE l.tenant_id='$TENANT_ID' UNION ALL SELECT 'whatsapp_configs', COUNT(*) FROM whatsapp_configs WHERE tenant_id='$TENANT_ID' UNION ALL SELECT 'tenant_subscriptions', COUNT(*) FROM tenant_subscriptions WHERE tenant_id='$TENANT_ID' UNION ALL SELECT 'appointments', COUNT(*) FROM appointments WHERE tenant_id='$TENANT_ID' UNION ALL SELECT 'chat_messages', COUNT(*) FROM chat_messages WHERE tenant_id='$TENANT_ID' UNION ALL SELECT 'tickets', COUNT(*) FROM tickets WHERE tenant_id='$TENANT_ID' UNION ALL SELECT 'business_services', COUNT(*) FROM business_services WHERE tenant_id='$TENANT_ID' ORDER BY 1;"
Write-Host ""
Write-Host "=== Done ===" -ForegroundColor Green
