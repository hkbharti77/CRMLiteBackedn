# WhatsApp & Event System Audit — CRMLite Backend

## 1. Webhook Ingestion Pipeline
The current pipeline is **Blocking & Synchronous**.

### [Flow Analysis]
`Meta Webhook (POST)` -> `Controller` -> `WhatsAppService (Logic)` -> `Response 200 OK`

- **The Problem**: If `WhatsAppService` takes 4 seconds (e.g., waiting for Gemini), Meta's server might consider the request timed out.
- **Result**: Meta retries. The server processes the same message twice. This is a classic **Retry Storm**.

## 2. Idempotency & Deduplication
- **Current implementation**: `ProcessedMessageRepository` checks for `message_id` before processing.
- **Risk**: The check and the save happen within the same transaction. If the transaction is slow and two retries hit simultaneously, both might see the message as "not processed" due to isolation levels.

## 3. Failure Recovery & Dead-Letters
- **Dead-Letter Queue (DLQ)**: Non-existent. If a message fails (e.g., DB exception), the webhook just returns a 500 error.
- **Event Ordering**: Since processing is synchronous, ordering is preserved for a single thread, but there's no guarantee if multiple retries are active.

## 4. Async Event System
- **Usage**: Good use of `ApplicationEventPublisher` for side effects like `EmailNotificationListener`.
- **Limitation**: `ApplicationEvent` is internal to the JVM. If the server crashes before the listener finishes, the event is lost forever.
- **Recommendation**: Move to an external Message Broker (Redis Pub/Sub or RabbitMQ) for reliable event delivery.

## 5. Scalability Risks
- **Thread Starvation**: Under high message volume, the Tomcat thread pool will be exhausted by slow AI/DB calls.
- **Backpressure**: There is no mechanism to slow down incoming webhooks. The system will simply crash or timeout under pressure.
- **WebSocket Scaling**: Currently not implemented/observed, but real-time UI updates will require a STOMP/Redis solution to scale horizontally.
