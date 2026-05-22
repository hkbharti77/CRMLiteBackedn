# Backend Architecture Audit — CRMLite Backend

## 1. Core Architectural Flow
The application follows a standard **Controller -> Service -> Repository** flow, but with a heavy emphasis on **Event-Driven Side Effects**.

### [Webhook Ingestion Flow]
1. `WhatsAppWebhookController` receives raw JSON.
2. `WhatsAppService.processWebhook` parses the metadata and routes to the correct tenant.
3. Logic branches:
   - **Flow Engine**: `WhatsAppFlowService` checks if the user is in a stateful flow.
   - **AI/RAG**: If not in a flow, `RagGuardrailService` evaluates the text signal.
   - **Manual/Direct**: If neither, it's treated as a standard CRM message.

## 2. Deep Analysis

### [Service Boundaries]
- **Issue**: High boundary erosion.
- **Observation**: `WhatsAppService` (God Object) directly modifies `Lead` entities, triggers `Appointment` logic, and handles AI fallbacks. 
- **Impact**: Change in one domain (e.g., Lead management) requires testing the entire WhatsApp engine.

### [Async Processing & Threading]
- **Current State**: Uses `@EnableAsync` and `@EnableScheduling`.
- **Memory Risks**: Async methods like `EmailService.sendTemplate` and `RagRetrievalService.ingest` rely on an in-memory thread pool.
- **Bottleneck**: Under high load, the `TaskExecutor` could saturate, delaying critical WhatsApp webhooks (which are synchronous).

### [State Management]
- **Active Conversations**: Managed via `ConversationState` (Database). This is good for durability.
- **RAG Ingestion**: Managed via `HashMap` in `RagController`. **CRITICAL RISK**: Server restart loses all active ingestion progress tracking.

### [Scalability Bottlenecks]
- **Synchronous Webhooks**: The WhatsApp Webhook is processed synchronously. If the AI or DB is slow, Meta's servers will timeout and retry, potentially causing a **Retry Storm**.
- **Vector Search**: In-memory vector stores are efficient but limited by JVM heap. A massive tenant base could lead to OOM despite the circuit breaker.

## 3. Findings & Identified Anti-patterns

| Finding | Severity | Root Cause | Refactor Strategy |
| :--- | :--- | :--- | :--- |
| **God Object** (`WhatsAppService`) | P1 | Over-accumulation of responsibilities | Extract logic into `WebhookProcessor`, `MenuGenerator`, and `MessagingClient`. |
| **Blocking Webhook Ingestion** | P1 | Synchronous execution of business logic on the HTTP thread | Ingest webhook -> Save to DB -> Respond 200 OK immediately -> Process asynchronously via Queue. |
| **In-Memory Task Tracking** | P2 | Lack of persistent state for background jobs | Move `RagController` status map to a `JobStatus` database table. |
| **N+1 Potential in Flows** | P2 | Lazy loading of `Contact` and `User` within loops | Use `@EntityGraph` or `JOIN FETCH` in `ConversationStateRepository`. |

## 4. AI Request Lifecycle
1. **Signal Evaluation**: Fast (Regex/Scoring).
2. **Vector Retrieval**: Fast (In-memory cache).
3. **LLM Generation**: Slow (2-5 seconds).
- **Architecture Risk**: The LLM call is synchronous within the `WhatsAppService` flow. This blocks the webhook thread. If 10 users chat simultaneously, 10 threads are tied up for seconds.
