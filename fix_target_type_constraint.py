import psycopg2

try:
    print("Connecting to PostgreSQL database...")
    conn = psycopg2.connect(
        host="10.215.46.176",
        port="5432",
        database="chatcrmdb",
        user="u0_a425",
        password="Root@123"
    )
    conn.autocommit = True
    cur = conn.cursor()
    
    cur.execute("SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'whatsapp_campaigns'::regclass;")
    rows = cur.fetchall()
    print("=== whatsapp_campaigns constraints ===")
    for row in rows:
        print(f"{row[0]}: {row[1]}")
    
    cur.close()
    conn.close()

except Exception as e:
    print(f"Error querying database constraints: {e}")
