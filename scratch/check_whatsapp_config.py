import os, psycopg2, json

conn = psycopg2.connect(
    host="10.124.190.47",
    database="chatcrmdb",
    user="u0_a425",
    password="Root@123",
    port=5432
)
cur = conn.cursor()
cur.execute("SELECT id, tenant_id, phone_number_id, display_phone_number, waba_id, access_token, verified_name FROM whatsapp_configs;")
rows = cur.fetchall()
for row in rows:
    print(f"ID: {row[0]}")
    print(f"Tenant: {row[1]}")
    print(f"Phone ID: {row[2]}")
    print(f"Phone Number: {row[3]}")
    print(f"WABA ID: {row[4]}")
    print(f"Token Length: {len(str(row[5])) if row[5] else 0}")
    print(f"Token Starts with: {str(row[5])[:10]}...")
    print(f"Verified Name: {str(row[6]).encode('ascii', 'ignore').decode('ascii')}")
    print("-" * 20)

conn.close()
