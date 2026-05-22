# Distributed Systems Audit — CRMLite Backend

## 1. Local Memory State (The "Single Node" Trap)
The current architecture assumes a single application instance. Scaling to multiple nodes (Horizontal Scaling) will break several core features.

### [A] Guardrail & Deduplication Inconsistency
- **Issue**: `RagGuardrailService` stores user sessions in a `ConcurrentHashMap`.
- **Failure**: A user sends Message 1 to **Node A**. Node A records it. The user sends Message 2 (duplicate) which is routed by the Load Balancer to **Node B**.
- **Impact**: Node B has no memory of Message 1. The deduplication/spam detection fails. The AI is called twice.

### [B] Vector Store Desynchronization
- **Issue**: `LocalVectorStoreService` caches tenant embeddings in-memory.
- **Failure**: An admin uploads a new PDF to **Node A**. Node A updates its in-memory index. **Node B** still has the old index cached.
- **Impact**: Customers chatting with the bot on Node B get outdated/missing information.

### [C] WebSocket Fanout Failure
- **Issue**: `SimpMessagingTemplate` is used to push real-time chat updates to the dashboard.
- **Failure**: The Admin dashboard is connected via WebSocket to **Node A**. A WhatsApp message arrives at **Node B**.
- **Impact**: Node B broadcasts the message to its local subscribers (none). The Admin dashboard on Node A never sees the new message appear in the UI.

## 2. Shared Resource Coordination
### [A] Scheduled Task Collision
- **Issue**: `@Scheduled` tasks in `IdempotencyService` and `WhatsAppFlowService` (cleanup).
- **Failure**: At midnight, 5 nodes all try to run `DELETE FROM processed_messages WHERE ...`.
- **Impact**: Excessive DB lock contention and potential deadlocks.
- **Fix**: Use a distributed scheduler like **ShedLock**.

## 3. Distributed-Safe Redesign Strategy

| Component | Current State | Distributed Fix |
| :--- | :--- | :--- |
| **User Sessions** | `ConcurrentHashMap` | **Redis** (K-V store with TTL) |
| **Vector Store** | `Caffeine` / In-Memory | **pgvector** (DB-native search) or **Pinecone/Milvus** |
| **WebSockets** | Local Broker | **Redis Pub/Sub** or **RabbitMQ** Relay |
| **Idempotency** | DB Constraints | Continue with DB, or use **Redis Distributed Lock** |
| **Background Tasks** | `@Async` (Local) | **Spring Batch** or **Quartz** with DB backing |

## 4. Scaling Blocker: The JVM Heap
Since vector chunks are loaded into the JVM heap for every active tenant:
- **Node Memory** = `(Active Tenants) * (Avg Chunks per Tenant) * (Vector Dim Size)`.
- As you add more tenants, you MUST add more RAM or more nodes. 
- However, adding more nodes increases the **Sync Overhead** for the in-memory caches.
- **Verdict**: Horizontal scaling is currently linear in cost but complex in consistency. A move to a centralized Vector DB is mandatory for >100 enterprise tenants.
