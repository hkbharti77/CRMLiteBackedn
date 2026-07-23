import psycopg2
import os

try:
    conn = psycopg2.connect(
        host="10.85.229.4",
        port="5432",
        database="chatcrmdb",
        user="u0_a425",
        password="Root@123"
    )
    
    cur = conn.cursor()
    
    # First, let's find the user and their tenant_id
    cur.execute("SELECT id, email, tenant_id FROM app_users WHERE email = 'gyanvaniai@gmail.com'")
    user_row = cur.fetchone()
    
    if user_row:
        user_id, email, tenant_id = user_row
        print("=== User Details ===")
        print(f"User ID: {user_id}")
        print(f"Email: {email}")
        print(f"Tenant ID: {tenant_id}")
        print("\n")
        
        # Now fetch the whatsapp_configs for this tenant
        cur.execute("""
            SELECT *
            FROM whatsapp_configs 
            WHERE tenant_id = %s
        """, (tenant_id,))
        
        wa_config = cur.fetchone()
        if wa_config:
            # Let's get column names
            col_names = [desc[0] for desc in cur.description]
            print("=== WhatsApp Configuration ===")
            for name, val in zip(col_names, wa_config):
                if name == 'access_token' and val:
                    val = val[:15] + "..."
                print(f"{name}: {val}")
        else:
            print("No WhatsApp Configuration found for this user's Tenant ID.")
            
    else:
        print("User 'gyanvaniai@gmail.com' not found in the database.")
        
    cur.close()
    conn.close()
    
except Exception as e:
    print(f"Error querying database: {e}")
