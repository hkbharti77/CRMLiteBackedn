import os, psycopg2, json, urllib.request, urllib.error
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives import padding
import base64

conn = psycopg2.connect(
    host="10.124.190.47",
    database="chatcrmdb",
    user="u0_a425",
    password="Root@123",
    port=5432
)

# Java AES logic
def get_key(secret_str):
    secret_bytes = secret_str.encode('utf-8')
    key = bytearray(16)
    for i in range(16):
        if i < len(secret_bytes):
            key[i] = secret_bytes[i]
        else:
            key[i] = ord('0')
    return bytes(key)

secret_key_str = [line for line in open('.env') if line.startswith('ENCRYPTION_SECRET_KEY=')][0].split('=')[1].strip()
key = get_key(secret_key_str)

def decrypt(db_data):
    if not db_data or not db_data.startswith("ENC:"):
        return db_data
    try:
        payload = base64.b64decode(db_data[4:])
        cipher = Cipher(algorithms.AES(key), modes.ECB(), backend=default_backend())
        decryptor = cipher.decryptor()
        decrypted_padded = decryptor.update(payload) + decryptor.finalize()
        unpadder = padding.PKCS7(128).unpadder()
        decrypted = unpadder.update(decrypted_padded) + unpadder.finalize()
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
                data = json.loads(res.read())
                print("STATUS: OK ->", data.get('display_phone_number', data))
        except urllib.error.HTTPError as e:
            err = json.loads(e.read())
            print(f"STATUS: FAILED ({e.code}) -> {err.get('error', {}).get('message')}")
    else:
        print("TOKEN:", token)
    print("-" * 20)

conn.close()
