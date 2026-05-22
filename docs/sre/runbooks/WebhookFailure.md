# Runbook: Webhook Failure

**Severity:** P1 (High Impact)
**Component:** WhatsApp Webhook Ingress
**On-Call:** Backend Engineer / SRE

## Symptoms
- No new messages appearing in the CRM.
- Meta App Dashboard shows high error rate for webhooks.
- 5xx errors logged by `AuthTokenFilter` or `WhatsAppController`.
- `whatsapp.ingress.stream` in Redis is idle.

## Investigation Steps
1. **Check Ngrok/Public URL:**
   - Verify if `app.public.url` in `application.properties` matches the active Ngrok/Gateway URL.
   - Run `curl -I <public_url>/api/v1/whatsapp/webhook` to check connectivity.
2. **Verify Meta Configuration:**
   - Navigate to [Meta for Developers](https://developers.facebook.com/) -> App -> WhatsApp -> Configuration.
   - Check if "Webhook URL" is verified and active.
3. **Check Logs:**
   - Search for `Webhook signature mismatch` or `X-Hub-Signature-256` errors.
   - Check for `TenantContext` propagation warnings.
4. **Redis Health:**
   - Ensure Redis is accepting connections (`redis-cli ping`).

## Recovery Actions
- **Update Public URL:** If Ngrok restarted, update the `app.public.url` and Meta App configuration immediately.
- **Restart Ingress Nodes:** If the stream is stuck, restart the backend pods to re-initialize the Redis stream listeners.
- **Verification Reset:** If Meta lost verification, re-enter the `WHATSAPP_VERIFY_TOKEN` in the Meta dashboard.

## Rollback Procedures
- N/A (Webhooks are real-time; missed messages should be in the Meta Message Echo logs if enabled).
