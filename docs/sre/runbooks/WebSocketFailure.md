# Runbook: WebSocket Failure

**Severity:** P3 (User Experience)
**Component:** STOMP / WebSocket Handler
**On-Call:** Frontend/Backend Engineer

## Symptoms
- CRM dashboard not updating live when messages arrive.
- "Connection Lost" toast appearing in the frontend.
- `Handshake failed` logs in the backend.

## Investigation Steps
1. **NGINX/Gateway Config:**
   - Verify `Upgrade` and `Connection` headers are preserved by the reverse proxy.
2. **Concurrent Connections:**
   - Check `netstat` for excessive `ESTABLISHED` connections on port 8080.
3. **Session Expiry:**
   - Check if JWT expiration is causing silent disconnects.

## Recovery Actions
- **Restart Backend:** Force disconnect all clients to re-establish clean handshakes.
- **Bypass Cache:** If using a CDN (like Cloudflare), ensure WebSocket traffic is bypassed/allowed.

## Rollback Procedures
- N/A.
