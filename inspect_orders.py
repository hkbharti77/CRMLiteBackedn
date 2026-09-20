import psycopg2

conn = psycopg2.connect(
    host="192.168.117.66",
    port="5432",
    database="chatcrmdb",
    user="u0_a425",
    password="Root@123"
)
cur = conn.cursor()

print("--- TENANT PAYMENT CONFIGS ---")
cur.execute("""
    SELECT id, tenant_id, integration_type, is_active, status, webhook_key_hash
    FROM tenant_payment_configs
""")
configs = cur.fetchall()
for c in configs:
    print(f"Config ID: {c[0]}, Tenant: {c[1]}, Type: {c[2]}, Active: {c[3]}, Status: {c[4]}, WebhookKeyHash: {c[5]}")

cur.close()
conn.close()
