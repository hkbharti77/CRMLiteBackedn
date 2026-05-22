# System Failure Simulation Report — CRMLite Backend

This report simulates high-stress failure scenarios and analyzes the resulting system behavior.

---

### Scenario 1: 10,000 Webhook Retries in 2 Minutes
- **System Behavior**: Tomcat thread pool (200) is immediately overwhelmed. 
- **Breaking Point**: Thread exhaustion within ~5 seconds.
- **Cascade**: All other APIs (Admin Login, Lead View) return `504 Gateway Timeout` or `503 Service Unavailable`.
- **Mitigation**: Move webhook processing to a background worker pool with a bounded queue.

---

### Scenario 2: Gemini API Latency Spikes to 30 Seconds
- **System Behavior**: Because webhooks are synchronous, each thread waits 30s. 
- **Breaking Point**: After 200 messages arrive (even if unique), the system is fully blocked. Meta starts retrying because the response takes > 10s.
- **Cascade**: "Retry Storm" — the load triples every 10 seconds. Memory usage spikes due to 200+ active threads holding large context strings.
- **Mitigation**: Implement a strict 10s timeout on LLM calls and a circuit breaker that trips after 3 consecutive timeouts.

---

### Scenario 3: Database Outage / Connection Pool Exhaustion
- **System Behavior**: HikariCP starts throwing `Connection is not available, request timed out after 30000ms`.
- **Breaking Point**: When more than 10 concurrent requests (default pool size) involve slow DB queries + slow I/O.
- **Cascade**: The `IdempotencyService` and `MessageRepository` fail. Side effects (AI responses) might still fire if they happen before the DB call, leading to "Ghost Messages" in WhatsApp with no record in the CRM.
- **Mitigation**: Increase pool size to 50; use `@Async` for DB-heavy side effects.

---

### Scenario 4: One Tenant Uploads 5 Million Embeddings
- **System Behavior**: `RagIngestionService` starts a massive loop of embedding + saving.
- **Breaking Point**: `LocalVectorStoreService.preloadTenant` triggers an `OutOfMemoryError: Java heap space`.
- **Cascade**: The entire server process crashes. Since it's a monolith, **ALL tenants** are offline because of one bad actor.
- **Mitigation**: Implement tenant-level ingestion quotas (e.g., max 2,000 chunks).

---

### Scenario 5: Duplicate Webhook Replay Attack
- **System Behavior**: The attacker sends the same `waMessageId` 100 times/sec.
- **Breaking Point**: Since `IdempotencyService` is not used in the entry path, the system performs a `contactRepository.findByWaId` lookup for every request.
- **Cascade**: DB CPU spikes to 100% due to redundant index lookups.
- **Mitigation**: Check for `waMessageId` in a **Redis Bloom Filter** at the controller level.

---

### Scenario 6: Node Crash During Flow Execution
- **System Behavior**: The `ConversationState` is updated step-by-step.
- **Breaking Point**: If the crash happens between `advanceFlow` and `stateRepository.save()`.
- **Cascade**: The user's message is "Lost." When the node comes back, the user is still at the old step. They send the next answer, but the system thinks it's the answer to the *previous* question.
- **Mitigation**: Atomic transactions (already present) but need "In-Progress" job tracking for long-running AI steps.

---

### Scenario 7: Massive WebSocket Fanout
- **System Behavior**: An admin with 1,000 active leads opens the dashboard. 100 messages arrive simultaneously.
- **Breaking Point**: The WebSocket buffer overflows if the client is slow.
- **Cascade**: Browser crashes or laggy UI.
- **Mitigation**: Debounce UI updates; send "Batch" updates via WebSocket every 500ms instead of individual messages.
