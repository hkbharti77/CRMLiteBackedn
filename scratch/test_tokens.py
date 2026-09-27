import os, psycopg2, json, urllib.request, urllib.error
from cryptography.fernet import Fernet
import base64
import hashlib

conn = psycopg2.connect(
    host="10.124.190.47",
    database="chatcrmdb",
    user="u0_a425",
    password="Root@123",
    port=5432
)

# Encryption setup from backend code logic
secret_key = [line for line in open('.env') if line.startswith('ENCRYPTION_SECRET_KEY=')][0].split('=')[1].strip()
# Convert key to AES-256 (32 bytes) via SHA-256
key_bytes = hashlib.sha256(secret_key.encode('utf-8')).digest()
fernet = Fernet(base64.urlsafe_b64encode(key_bytes))

def decrypt(encrypted_value):
    if not encrypted_value or not str(encrypted_value).startswith("ENC:"):
        return encrypted_value
    try:
        b64_cipher = str(encrypted_value)[4:] # strip ENC:
        decrypted = fernet.decrypt(b64_cipher.encode('utf-8'))
        return decrypted.decode('utf-8')
    except Exception as e:
        return f"ERROR: {e}"

cur = conn.cursor()
cur.execute("SELECT tenant_id, phone_number_id, access_token, verified_name FROM whatsapp_configs;")
rows = cur.fetchall()
for row in rows:
    tenant_id = row[0]
    phone_id = row[1]
    enc_token = row[2]
    name = row[3]
    
    token = decrypt(enc_token)
    print(f"Tenant: {tenant_id}")
    print(f"Phone ID: {phone_id} (Name: {name})")
    
    if token and not token.startswith("ERROR"):
        url = f"https://graph.facebook.com/v19.0/{phone_id}?access_token={token}"
        req = urllib.request.Request(url)
        try:
            with urllib.request.urlopen(req) as res:
                print("STATUS: OK ->", json.loads(res.read())['display_phone_number'])
        except urllib.error.HTTPError as e:
            err = json.loads(e.read())
            print(f"STATUS: FAILED ({e.code}) -> {err.get('error', {}).get('message')}")
    else:
        print("TOKEN:", token)
    print("-" * 20)

conn.close()
