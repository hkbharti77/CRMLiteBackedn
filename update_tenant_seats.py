import psycopg2

try:
    conn = psycopg2.connect(
        dbname="chatcrmdb",
        user="u0_a425",
        password="Root@123",
        host="10.215.46.176",
        port=5432
    )
    cur = conn.cursor()

    cur.execute("UPDATE subscription_plans SET employee_limit = 10 WHERE id = 'FREE';")
    conn.commit()

    tenant_id = 'a450c865-9613-4dec-a44d-9b781418b96a'
    cur.execute("SELECT id FROM tenant_subscriptions WHERE tenant_id = %s", (tenant_id,))
    row = cur.fetchone()

    if row:
        cur.execute("UPDATE tenant_subscriptions SET plan_id = 'PRO', status = 'ACTIVE' WHERE tenant_id = %s", (tenant_id,))
    else:
        cur.execute("""
            INSERT INTO tenant_subscriptions (id, tenant_id, plan_id, status, billing_cycle, current_period_start, current_period_end)
            VALUES (gen_random_uuid(), %s, 'PRO', 'ACTIVE', 'MONTHLY', NOW(), NOW() + INTERVAL '10 years')
        """, (tenant_id,))
    conn.commit()

    print("Updated subscription and employee limits successfully!")

    cur.execute("SELECT id, name, employee_limit FROM subscription_plans;")
    print("Plans:", cur.fetchall())

    cur.execute("SELECT tenant_id, plan_id, status FROM tenant_subscriptions;")
    print("Subscriptions:", cur.fetchall())

    cur.close()
    conn.close()
except Exception as e:
    print("Error:", e)
