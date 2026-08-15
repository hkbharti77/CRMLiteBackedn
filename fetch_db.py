import psycopg2
import os

try:
    conn = psycopg2.connect(
        host="10.215.46.176",
        port="5432",
        database="chatcrmdb",
        user="u0_a425",
        password="Root@123"
    )
    cur = conn.cursor()
    
    print("=== All WhatsApp Configs ===")
    cur.execute("SELECT id, tenant_id, phone_number_id, business_id, access_token IS NOT NULL AS has_token FROM whatsapp_configs")
    configs = cur.fetchall()
    for c in configs:
        print(f"ID: {c[0]}, TenantID: {c[1]}, PhoneNumberID: {c[2]}, BusinessID: {c[3]}, HasToken: {c[4]}")
    
    print("\n=== Contacts & Bot Status ===")
    cur.execute("SELECT id, name, wa_id, bot_paused, escalated, latest_sentiment FROM contacts ORDER BY id DESC LIMIT 10")
    contacts = cur.fetchall()
    for ct in contacts:
        print(f"Contact ID: {ct[0]}, Name: {ct[1]}, WA_ID: {ct[2]}, BotPaused: {ct[3]}, Escalated: {ct[4]}, Sentiment: {ct[5]}")

    cur.close()
    conn.close()
except Exception as e:
    print(f"Error querying database: {e}")
        
    cur.close()
    conn.close()
    
except Exception as e:
    print(f"Error querying database: {e}")
