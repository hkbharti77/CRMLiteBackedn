import urllib.request
import json
import time

payload = {
    "object": "whatsapp_business_account",
    "entry": [{
        "id": "1766319617855677",
        "changes": [{
            "value": {
                "messaging_product": "whatsapp",
                "metadata": {
                    "display_phone_number": "919905941728",
                    "phone_number_id": "1131104486761805"
                },
                "contacts": [{"profile": {"name": "Test User"}, "wa_id": "919905941728"}],
                "messages": [{
                    "from": "919905941728",
                    "id": "test_ngrok_123",
                    "timestamp": str(int(time.time())),
                    "text": {"body": "Hello via ngrok test"},
                    "type": "text"
                }]
            },
            "field": "messages"
        }]
    }]
}

payload_bytes = json.dumps(payload).encode('utf-8')

url_ngrok = 'https://marg-estival-kalyn.ngrok-free.dev/api/v1/webhook/whatsapp'
url_local = 'http://localhost:8080/api/v1/webhook/whatsapp'

for label, url in [("NGROK (External)", url_ngrok), ("LOCALHOST (Direct)", url_local)]:
    print("")
    print(f"=== Testing via {label} ===")
    req = urllib.request.Request(url, data=payload_bytes, 
                                  headers={'Content-Type': 'application/json'}, method='POST')
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            print(f"OK Status: {r.getcode()}")
            print(f"Response: {r.read().decode()}")
    except urllib.error.HTTPError as e:
        print(f"FAIL HTTP Error: {e.code}")
        print(f"Body: {e.read().decode()}")
    except Exception as e:
        print(f"ERROR: {type(e).__name__}: {e}")
