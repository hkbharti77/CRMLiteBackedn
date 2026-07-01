import psycopg2
import sys
from datetime import datetime

DB_URL = "postgresql://crmlite_user:6gruN8FyhnKFApb2r2pYFBgkB8B86CJE@dpg-d91gebjtqb8s7390bh9g-a.ohio-postgres.render.com:5432/crmlite"
TENANT_ID = "f2743829-9058-4a1e-aaf0-1a536e6508b9"

def main():
    print("Connecting to DB...")
    conn = psycopg2.connect(DB_URL)
    cur = conn.cursor()
    print("Connected OK")

    # Step 1: List all tables
    cur.execute("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename;")
    tables = [r[0] for r in cur.fetchall()]
    print("\nALL TABLES:", tables)

    # Step 2: Check tenant
    cur.execute("SELECT id, business_name, plan_type FROM tenants WHERE id = %s", (TENANT_ID,))
    tenant = cur.fetchone()
    print("\nTENANT ROW:", tenant)

    # Step 3: Check subscription
    cur.execute("SELECT id, plan_id, status FROM tenant_subscriptions WHERE tenant_id = %s", (TENANT_ID,))
    sub = cur.fetchone()
    print("SUBSCRIPTION ROW:", sub)

    # Step 4: Find user data for this tenant
    user_data = None
    user_tables = [t for t in tables if "user" in t.lower() or "account" in t.lower()]
    print("\nUser-like tables:", user_tables)

    for t in user_tables:
        try:
            cur.execute(
                "SELECT column_name FROM information_schema.columns WHERE table_name=%s ORDER BY ordinal_position",
                (t,)
            )
            cols = [r[0] for r in cur.fetchall()]
            print(f"Table '{t}' columns: {cols}")
            if "tenant_id" in cols:
                cur.execute(f"SELECT * FROM {t} WHERE tenant_id=%s LIMIT 2", (TENANT_ID,))
                rows = cur.fetchall()
                if rows:
                    print(f"  -> Found rows: {rows}")
                    user_data = rows[0]
        except Exception as e:
            print(f"  -> Error on {t}: {e}")

    # Step 5: Insert missing tenant if needed
    if tenant is None:
        print("\nTenant is MISSING. Inserting now...")

        # Derive business name
        bname = "Business"
        if user_data:
            # Try to find name or email column
            for val in user_data:
                if isinstance(val, str) and "@" not in str(val) and len(str(val)) > 2:
                    bname = str(val)
                    break

        cur.execute("""
            INSERT INTO tenants (
                id, business_name, business_type, business_sub_type,
                plan_type, onboarding_completed, primary_resource, created_at
            )
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (id) DO NOTHING
        """, (
            TENANT_ID,
            bname,
            "freelance-web-graphic-designers",
            "freelance-web-graphic-designers",
            "PRO",
            True,
            "LEAD",
            datetime.now()
        ))
        conn.commit()
        print(f"SUCCESS: Tenant inserted with business_name='{bname}'")

        # Verify
        cur.execute("""
            SELECT t.id, t.business_name, t.plan_type, ts.plan_id, ts.status
            FROM tenants t
            LEFT JOIN tenant_subscriptions ts ON ts.tenant_id = t.id
            WHERE t.id = %s
        """, (TENANT_ID,))
        result = cur.fetchone()
        print("VERIFY RESULT:", result)
    else:
        print("\nTenant already exists. No insert needed.")
        print(f"  id={tenant[0]}, name={tenant[1]}, plan={tenant[2]}")
        if sub:
            print(f"  subscription: plan={sub[1]}, status={sub[2]}")

    cur.close()
    conn.close()
    print("\nDONE.")

if __name__ == "__main__":
    main()
