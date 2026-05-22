# Backpressure & Load Management Audit — CRMLite Backend

## 1. The "Sync-Wait" Bottleneck
The system handles incoming webhooks synchronously within the Tomcat thread pool.

### [Overload Scenario]
- **Tomcat Default**: 200 threads.
- **AI Latency**: ~3 seconds per request.
- **Math**: 200 / 3 = **~66 requests per second (RPS)** is the absolute theoretical maximum throughput.
- **Reality**: If 100 users message the bot at the exact same second, 100 threads are blocked waiting for Gemini. If those 100 users send another message before the first 3s finish, **Tomcat is exhausted**.
- **Impact**: The health check fails, the dashboard becomes unresponsive, and new webhooks are rejected with `503 Service Unavailable`.

## 2. Retry Amplification (The "Death Spiral")
WhatsApp retries exponentially if it doesn't get a 200 OK within ~10-20 seconds.

1. **Slow LLM** causes a request to take 15 seconds.
2. **Meta** times out at 10 seconds and sends a **Retry**.
3. **Thread 2** starts processing the retry.
4. Now you have **2 threads** working on the same user, consuming **2x LLM credits** and **2x CPU**.
5. The system slows down further.
6. **Thread 3** (second retry) arrives.
7. **System Collapse**.

## 3. Unbounded Resources & Starvation
### [DB Connection Pool]
- **Hikari Default**: 10 connections.
- **Problem**: If 200 threads are active but only 10 can talk to the DB, 190 threads are now blocked on `getConnection()`. 
- **Impact**: Any non-WhatsApp API (like the Admin Login) will also block and timeout.

### [AI Concurrency]
- No global limit on concurrent AI calls. If 1,000 users hit the system, it will try to open 1,000 outbound HTTP connections to Google Gemini. 
- **Impact**: Local NAT port exhaustion or Gemini API rate-limiting (`429 Too Many Requests`).

## 4. Mitigation Strategies

| Risk | Mitigation |
| :--- | :--- |
| **Thread Starvation** | **Asynchronous Webhooks**: Immediately return 200 OK and push the payload to a persistent queue (Redis/DB). Process with a dedicated worker pool. |
| **Slow Dependencies** | **Bulkheads**: Isolate AI processing into a separate thread pool with a fixed size and a **bounded queue**. |
| **Retry Storms** | **Idempotency Key**: Check for `waMessageId` in a fast cache (Redis) before doing *any* work. |
| **Resource Exhaustion** | **Load Shedding**: If the queue depth > X, reject incoming webhooks immediately with a custom "Server Busy" logic. |
| **Tenant Fairness** | **Adaptive Throttling**: Use Bucket4j to limit each tenant to a specific RPS, preventing one "viral" tenant from crashing the system for others. |

## 5. Critical Recommendation: The "In-Process" Queue
If moving to Redis is too much effort for Phase 1, use a **Bounded BlockingQueue**:
1. Controller receives Webhook.
2. Controller puts payload in `LinkedBlockingQueue(capacity=1000)`.
3. If queue is full, return 429 or 503.
4. Worker threads poll the queue.
5. Meta gets a 200 OK instantly, preventing Retries.
