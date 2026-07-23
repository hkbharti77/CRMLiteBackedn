import hmac
import hashlib
import json
import urllib.request
import time
import sys

# Constants
APP_SECRET = "a79f5dc377d4d929896861aeac36b7ef"
PHONE_NUMBER_ID = "1131104486761805"
WABA_ID = "1766319617855677"

# Create a mock payload that mimics Meta's structure
payload_dict = {
    "object": "whatsapp_business_account",
    "entry": [
        {
            "id": WABA_ID,
            "changes": [
                {
                    "value": {
                        "messaging_product": "whatsapp",
                        "metadata": {
                            "display_phone_number": "990594172",
                            "phone_number_id": PHONE_NUMBER_ID
                        },
                        "contacts": [
                            {
                                "profile": {
                                    "name": "Test User"
                                },
                                "wa_id": "919876543210"
                            }
                        ],
                        "messages": [
                            {
                                "from": "919876543210",
                                "id": "wamid.HBgLOTE5ODc2NTQzMjEwFQIAEhgUM0E2OEQzRDE4MEEwOTYxMkMzMUUA",
                                "timestamp": str(int(time.time())),
                                "text": {
                                    "body": "Hi"
                                },
                                "type": "text"
                            }
                        ]
                    },
                    "field": "messages"
                }
            ]
        }
    ]
}

payload_bytes = json.dumps(payload_dict, separators=(',', ':')).encode('utf-8')

# Generate HMAC SHA256 signature
signature = hmac.new(APP_SECRET.encode('utf-8'), payload_bytes, hashlib.sha256).hexdigest()
headers = {
    'Content-Type': 'application/json',
    'X-Hub-Signature-256': f'sha256={signature}'
}

print(f"Sending payload with Phone Number ID: {PHONE_NUMBER_ID}")
print(f"Signature: sha256={signature}")

req = urllib.request.Request("http://localhost:8080/api/v1/webhook/whatsapp", data=payload_bytes, headers=headers, method='POST')

try:
    with urllib.request.urlopen(req) as response:
        print(f"Response Code: {response.getcode()}")
        print(f"Response Body: {response.read().decode('utf-8')}")
except Exception as e:
    print(f"Error: {e}")
