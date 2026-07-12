# ============================================================
# migrate_user_to_remote.ps1  (v3 — COPY CSV approach)
# Uses CSV format which correctly handles newlines in text fields
# ============================================================

$psql      = "C:\Program Files\PostgreSQL\18\bin\psql.exe"
$localConn = "postgresql://root:root@localhost:5433/chatcrmdb"
$remoteConn= "postgresql://crmlite_user:6gruN8FyhnKFApb2r2pYFBgkB8B86CJE@dpg-d91gebjtqb8s7390bh9g-a.ohio-postgres.render.com:5432/crmlite"
$csvDir    = "$PSScriptRoot\csv_export"

$TENANT_ID = "9431bdc0-7a42-4f5e-a86a-fc2b174f08bf"
$USER_ID   = "f2743829-9058-4a1e-aaf0-1a536e6508b9"

# ── Setup ────────────────────────────────────────────────────────────────────
New-Item -ItemType Directory -Force -Path $csvDir | Out-Null
$importLines = @()

# ── Helper: export table to CSV from local, add \copy line for remote ─────────
function Export-Table {
    param([string]$Table, [string]$Query)

    $csvPath = "$csvDir\$Table.csv"

    Write-Host "  Exporting $Table..." -NoNewline

    & $psql $localConn -c "COPY ($Query) TO STDOUT WITH CSV HEADER" | `
        Out-File -FilePath $csvPath -Encoding utf8

    $lines = (Get-Content $csvPath | Measure-Object -Line).Lines
    if ($lines -le 1) {
        Write-Host " (empty)" -ForegroundColor Yellow
        return  # header-only = no rows
    }
    Write-Host " $($lines-1) rows" -ForegroundColor Green
}

Write-Host "`n=== Step 1: Export from local DB (COPY CSV) ===" -ForegroundColor Cyan

$tables = [ordered]@{}
function Register-Table { param([string]$T, [string]$Q); $tables[$T] = $Q }

Register-Table "tenants"              "SELECT * FROM tenants WHERE id = '$TENANT_ID'"
Register-Table "app_users"            "SELECT * FROM app_users WHERE id = '$USER_ID'"
Register-Table "whatsapp_configs"     "SELECT * FROM whatsapp_configs WHERE tenant_id = '$TENANT_ID'"
Register-Table "tenant_subscriptions" "SELECT * FROM tenant_subscriptions WHERE tenant_id = '$TENANT_ID'"
Register-Table "contacts"             "SELECT * FROM contacts WHERE tenant_id = '$TENANT_ID'"
Register-Table "leads"                "SELECT * FROM leads WHERE tenant_id = '$TENANT_ID'"
Register-Table "lead_enquiries"       "SELECT le.* FROM lead_enquiries le JOIN leads l ON le.lead_id = l.id WHERE l.tenant_id = '$TENANT_ID'"
Register-Table "appointments"         "SELECT * FROM appointments WHERE tenant_id = '$TENANT_ID'"
Register-Table "chat_messages"        "SELECT * FROM chat_messages WHERE tenant_id = '$TENANT_ID'"
Register-Table "tickets"              "SELECT * FROM tickets WHERE tenant_id = '$TENANT_ID'"
Register-Table "business_services"    "SELECT * FROM business_services WHERE tenant_id = '$TENANT_ID'"
Register-Table "activity_logs"        "SELECT * FROM activity_logs WHERE owner_id = '$USER_ID'"
Register-Table "user_sessions"        "SELECT * FROM user_sessions WHERE user_id = '$USER_ID'"
Register-Table "billing_transactions" "SELECT * FROM billing_transactions WHERE tenant_id = '$TENANT_ID'"

# Export all tables
foreach ($entry in $tables.GetEnumerator()) {
    Export-Table $entry.Key $entry.Value
}

Write-Host "`n=== Step 2: Import into remote Render DB (COPY with explicit column list) ===`n" -ForegroundColor Cyan
foreach ($entry in $tables.GetEnumerator()) {
    $table   = $entry.Key
    $csvPath = "$csvDir\$table.csv"
    if (-not (Test-Path $csvPath)) { continue }
    $lines = (Get-Content $csvPath | Measure-Object -Line).Lines
    if ($lines -le 1) { Write-Host "  Skip $table (empty)" -ForegroundColor Yellow; continue }

    # Read the CSV header row to get column names in LOCAL DB order
    # Pass them explicitly so COPY maps by NAME not by POSITION
    $csvHeader = (Get-Content $csvPath -TotalCount 1).Trim()

    Write-Host "  Importing $table ($($lines-1) rows)..." -NoNewline
    Get-Content $csvPath | & $psql $remoteConn -c "COPY $table ($csvHeader) FROM STDIN WITH CSV HEADER"
    Write-Host " done" -ForegroundColor Green
}

Write-Host "`n=== Step 3: Verify counts in remote DB ===`n" -ForegroundColor Cyan
& $psql $remoteConn -c "
SELECT 'tenants'              AS tbl, COUNT(*) FROM tenants             WHERE id = '$TENANT_ID'
UNION ALL SELECT 'app_users',          COUNT(*) FROM app_users          WHERE tenant_id = '$TENANT_ID'
UNION ALL SELECT 'contacts',           COUNT(*) FROM contacts           WHERE tenant_id = '$TENANT_ID'
UNION ALL SELECT 'leads',              COUNT(*) FROM leads              WHERE tenant_id = '$TENANT_ID'
UNION ALL SELECT 'whatsapp_configs',   COUNT(*) FROM whatsapp_configs   WHERE tenant_id = '$TENANT_ID'
UNION ALL SELECT 'tenant_subscriptions', COUNT(*) FROM tenant_subscriptions WHERE tenant_id = '$TENANT_ID'
UNION ALL SELECT 'appointments',       COUNT(*) FROM appointments       WHERE tenant_id = '$TENANT_ID'
UNION ALL SELECT 'chat_messages',      COUNT(*) FROM chat_messages      WHERE tenant_id = '$TENANT_ID'
UNION ALL SELECT 'tickets',            COUNT(*) FROM tickets            WHERE tenant_id = '$TENANT_ID'
UNION ALL SELECT 'business_services',  COUNT(*) FROM business_services  WHERE tenant_id = '$TENANT_ID'
ORDER BY 1;
"

Write-Host "`n=== Done! ===" -ForegroundColor Green
