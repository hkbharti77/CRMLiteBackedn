import os, json, urllib.request, urllib.error

key = [line for line in open('.env') if line.startswith('SARVAM_API_KEY=')][0].split('=')[1].strip()

req = urllib.request.Request("https://api.sarvam.ai/text-to-speech", data=json.dumps({
    "inputs": ["Hello world"],
    "target_language_code": "hi-IN",
    "speaker": "simran",
    "model": "bulbul:v3",
    "pace": 1.0,
    "speech_sample_rate": 22050
}).encode('utf-8'), headers={'api-subscription-key': key, 'Content-Type': 'application/json', 'Accept': 'application/octet-stream'})
try:
    with urllib.request.urlopen(req) as response:
        data = response.read()
        print("Data length:", len(data))
        print("First 32 bytes hex:", data[:32].hex())
except Exception as e:
    if hasattr(e, 'read'):
        print(e.read())
    print("Error:", e)
