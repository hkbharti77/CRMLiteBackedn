# Concurrency & Race Condition Audit — CRMLite Backend

## 1. The "Invisible" Idempotency Gap
### [Failure Scenario]
Meta (WhatsApp) sends a webhook. The backend starts processing. Due to a slow LLM call (3-5 seconds), Meta's server times out (typically 10s) or the connection jitters. Meta retries.
Two threads now process the **exact same `waMessageId`**.

### [Race Window]
In `WhatsAppService.java`, the system performs several side effects (resolving contacts, checking flows, calling AI) **BEFORE** saving the message to the database.

### [Sequence of Failure]
1. **Thread A** receives `waMessageId: 101`.
2. **Thread A** calls `ragRetrievalService.getAiResponse()` (Slow).
3. **Thread B** receives `waMessageId: 101` (Meta Retry).
4. **Thread B** calls `ragRetrievalService.getAiResponse()`.
5. **Thread A** finishes AI, calls `whatsappClient.sendMessage()`. **[SIDE EFFECT 1]**
6. **Thread B** finishes AI, calls `whatsappClient.sendMessage()`. **[SIDE EFFECT 2 - DUPLICATE]**
7. **Thread A** calls `messageRepository.save()`. Success.
8. **Thread B** calls `messageRepository.save()`. **DataIntegrityViolationException** (Unique Constraint).
9. **Thread B** returns `500 Internal Server Error` to Meta.
10. **Meta** sees 500, retries again. Loop continues until Thread B's attempt hits the `REUSE` logic in Guardrails, but even `REUSE` calls `saveIncomingMessage` which will keep failing.

### [Production Impact]
- **User Experience**: User receives 2 or 3 duplicate AI responses for a single question.
- **Cost**: Double/Triple AI token consumption for the same query.
- **Reliability**: Thread starvation and logs flooded with constraint violations.

### [Engineering Fix]
Implement an **INSERT-FIRST** idempotency check at the very entry of the webhook handler.
```java
// Correct Pattern in WhatsAppService
if (!idempotencyService.markAsProcessed(waMessageId, owner)) {
    log.info("Duplicate message {} already being processed. Dropping.", waMessageId);
    return; // 200 OK to Meta
}
```

---

## 2. Conversation State Race (Lost Updates)
### [Failure Scenario]
A user sends two messages in rapid succession (e.g., "Yes" followed by "My email is test@test.com").
- **Thread A** processes "Yes" (updating flow to 'AWAITING_EMAIL').
- **Thread B** processes "My email..." (reading state while Thread A hasn't committed yet).

### [Race Window]
`WhatsAppFlowService.processFlow` is `@Transactional`.
1. **Thread A**: `stateRepository.findByContact(contact)` -> Returns State(Step: 1).
2. **Thread B**: `stateRepository.findByContact(contact)` -> Returns State(Step: 1) [Stale!].
3. **Thread A**: Updates State to Step 2, `save()`.
4. **Thread B**: Updates State (based on Step 1 logic) to something else, `save()`.
5. **Result**: Thread A's update is overwritten by Thread B. The conversation flow is now corrupted.

### [Production Impact]
Users get stuck in loops, or the system captures incorrect data for leads/appointments.

### [Engineering Fix]
Use **Pessimistic Locking** on the `ConversationState` table for the duration of the flow processing.
```java
// In ConversationStateRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<ConversationState> findByContact(Contact contact);
```

---

## 3. Shared Mutable State (Guardrail Memory)
### [Analysis]
`RagGuardrailService` uses `ConcurrentHashMap` for `userSessions`. 
While the map itself is thread-safe, the **read-modify-write** cycle on `UserSession` objects is NOT atomic.

```java
// RagGuardrailService.java
UserSession session = userSessions.computeIfAbsent(userId, k -> new UserSession());
int strikes = session.getJunkCount().incrementAndGet(); // Atomic integer is fine
// ... but logic like this:
if (session.getLastMessage().equals(normalizedText) && ...) {
    // There is no lock on 'session' during this check
}
```
If two threads update the same session, one might overwrite the `lastMessage` or `lastContextKey` of the other, leading to inconsistent deduplication decisions.

### [Fix]
Use `session.compute(...)` or a synchronized block on the session object if high-precision consistency is required for AI guardrails.
