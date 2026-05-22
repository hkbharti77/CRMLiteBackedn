# Refactor Roadmap — CRMLite Backend

## Phase 0: Immediate Security Hardening (P0)
**Goal: Secure the platform.**
- [ ] Move hardcoded secrets to environment variables.
- [ ] Implement strict CORS policy.
- [ ] Fix `/logout` session revocation.
- [ ] Audit all PII logging.

## Phase 1: Stability & Persistence (P1)
**Goal: Prevent data loss and handle load.**
- [ ] Migrate `RagController` status tracking from `HashMap` to Database.
- [ ] Implement a global `@TenantFilter` for all JPA queries.
- [ ] Add basic Health Check endpoints (Actuator).
- [ ] Start a unit test suite for `WhatsAppFlowService`.

## Phase 2: Architectural Decoupling (P1-P2)
**Goal: Breakdown God Objects.**
- [ ] Extract `WebhookProcessor` from `WhatsAppService`.
- [ ] Move WhatsApp List/Menu generation to a `WhatsAppMenuService`.
- [ ] Implement an asynchronous Webhook Processing queue (e.g., using Spring `@Async` or a dedicated task table).

## Phase 3: Scalability & Observability (P2)
**Goal: Prepare for 10x growth.**
- [ ] Move in-memory `userSessions` and `aiHits` to **Redis**.
- [ ] Implement **Prometheus** metrics for AI latency and DB pool health.
- [ ] Implement **Sleuth/Zipkin** for request tracing.
- [ ] Optimize Vector Storage: Evaluate moving to `pgvector` native search to free up JVM heap.

## Phase 4: Enterprise Features (P3)
**Goal: Advanced CRM capabilities.**
- [ ] Implement table partitioning for `messages`.
- [ ] Add support for multiple AI models (A/B Testing).
- [ ] Implement a full "Flow Builder" UI that persists flows to the DB instead of JSON files.
- [ ] Add multi-region redundancy.

## Priority Matrix

| Task | Effort | Impact | Priority |
| :--- | :--- | :--- | :--- |
| **Secrets Fix** | Low | High | **P0** |
| **Logout Fix** | Low | Medium | **P0** |
| **Queue Adoption** | Medium | High | **P1** |
| **Redis Migration** | Medium | Medium | **P2** |
| **God Class Breakup** | High | High | **P1** |
| **pgvector Native** | High | High | **P3** |
