import os
import psycopg2
from urllib.parse import urlparse

# Load .env
env_file = os.path.join(os.path.dirname(__file__), "..", ".env")
env_vars = {}
if os.path.exists(env_file):
    with open(env_file, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env_vars[k.strip()] = v.strip()

db_url = env_vars.get("SPRING_DATASOURCE_URL", "")
if db_url.startswith("jdbc:postgresql://"):
    db_url = db_url.replace("jdbc:postgresql://", "postgresql://")

db_user = env_vars.get("DB_USERNAME", "u0_a425")
db_pass = env_vars.get("DB_PASSWORD", "Root@123")

print(f"Connecting to DB: {db_url} with user: {db_user}")

try:
    parsed = urlparse(db_url)
    host = parsed.hostname or "192.168.117.66"
    port = parsed.port or 5432
    dbname = parsed.path.lstrip("/") or "chatcrmdb"

    conn = psycopg2.connect(
        host=host,
        port=port,
        dbname=dbname,
        user=db_user,
        password=db_pass
    )
    conn.autocommit = True
    cur = conn.cursor()
    print("Connected successfully!")

    queries = [
        "ALTER TABLE commerce_products ADD COLUMN IF NOT EXISTS sale_price NUMERIC(12, 2);",
        "ALTER TABLE commerce_products ADD COLUMN IF NOT EXISTS category VARCHAR(255);",
        "ALTER TABLE commerce_products ADD COLUMN IF NOT EXISTS availability VARCHAR(50);",
        "ALTER TABLE commerce_products ADD COLUMN IF NOT EXISTS product_condition VARCHAR(50);",
        "ALTER TABLE commerce_products ADD COLUMN IF NOT EXISTS url VARCHAR(1024);"
    ]

    for q in queries:
        print(f"Executing: {q}")
        cur.execute(q)

    print("All ALTER TABLE queries executed successfully!")
    cur.close()
    conn.close()
except Exception as e:
    print(f"Error: {e}")
