# Runbook: Redis Failure

**Severity:** P1 (High Impact)
**Component:** Redis / Caching Layer
**On-Call:** SRE

## Symptoms
- `RedisConnectionException` in logs.
- WebSocket updates failing.
- AI Cost Tracking and Quotas not updating.
- Webhook deduplication failing (duplicate messages).

## Investigation Steps
1. **Connectivity:**
   - Run `redis-cli -h ${REDIS_HOST} ping`.
2. **Memory Usage:**
   - Run `INFO memory` in `redis-cli`. Check for `maxmemory` evictions.
3. **Persistence Latency:**
   - Check if RDB/AOF saves are hanging the process.

## Recovery Actions
- **Restart Redis:** If unresponsive, perform a cold restart.
- **Clear Cache:** `FLUSHDB` if corrupted state is suspected (Warning: This will clear all active WhatsApp sessions).
- **Scale Redis:** Increase memory allocation if OOM is frequent.

## Rollback Procedures
- N/A.
