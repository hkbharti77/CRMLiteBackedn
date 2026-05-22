# CRMLite Service Decomposition Manifest

## 1. Webhook Ingress Service (Edge)
- **Responsibility**: Receive Meta Webhooks, validate signatures, and enqueue raw messages.
- **Protocol**: HTTP/HTTPS (Public) -> Redis Stream (Internal).
- **Scale Factor**: High traffic, stateless.
- **Migration**: Phase 1 (Strangler).

## 2. AI Orchestrator Service (Compute)
- **Responsibility**: LLM routing, RAG retrieval, guardrails, and governance.
- **Protocol**: gRPC (Synchronous) for real-time chat, Redis Stream (Async) for long tasks.
- **Scale Factor**: CPU/GPU intensive.
- **Migration**: Phase 2.

## 3. Workflow Engine Service (State)
- **Responsibility**: Conversation state management, business rules, and step progression.
- **Protocol**: Event Sourcing (Internal).
- **Scale Factor**: Memory intensive (State tracking).
- **Migration**: Phase 3 (The Monolith Core).

---

## Migration Strategy: The "Event Bridge" Pattern

To safely migrate, we will use a **Synchronized Event Bridge**:

1. **Monolith** continues to own the database.
2. **New Service** is deployed.
3. **Event Bridge** replicates events from the Monolith `ConversationEventStore` to the New Service's local store.
4. **Validation**: Run both systems in parallel; verify New Service outputs match the Monolith.
5. **Cutover**: Point the Ingress traffic to the New Service; keep the Monolith as a read-only fallback.
