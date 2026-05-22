# Runbook: Queue Overload

**Severity:** P2 (Moderate Impact - Latency)
**Component:** Redis Streams / FlowWorker
**On-Call:** Backend Engineer

## Symptoms
- High latency in WhatsApp message responses.
- `whatsapp.async.stream.max-len` threshold exceeded.
- Log message: `Queue backlog exceeds threshold for stream: whatsapp:ingress:stream`.
- Grafana shows `queue_backlog_count` climbing.

## Investigation Steps
1. **Check Consumer Lag:**
   - Run `XINFO GROUPS whatsapp:ingress:stream` in `redis-cli`.
   - Identify if `pending` messages are increasing.
2. **Resource Exhaustion:**
   - Check CPU/Memory of `FlowWorker` pods.
   - Check if LLM calls are timing out (causing workers to hang).
3. **Dead Letter Queue (DLQ):**
   - Check `whatsapp:ingress:dlq` for recurring failures causing retries.

## Recovery Actions
- **Scale Workers:** Increase `whatsapp.async.worker.concurrency` in `application.properties` or scale the number of application nodes.
- **Purge DLQ:** If a specific poisoned message is blocking processing, purge the DLQ.
- **Temporary Throttling:** Enable `Bucket4j` rate limiting on the webhook ingress to drop new messages until the backlog clears.

## Rollback Procedures
- Decrement worker count once `pending` count reaches near zero.
