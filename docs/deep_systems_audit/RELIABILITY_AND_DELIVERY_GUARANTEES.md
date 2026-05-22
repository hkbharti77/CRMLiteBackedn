# Reliability & Failure Semantics Audit — CRMLite Backend

## 1. The Delivery Guarantee Paradox
### [The Assumption]
The system assumes "Exactly-Once" processing by using a unique constraint on `waMessageId`.

### [The Reality: At-Least-Once]
- **Meta (WhatsApp)** guarantees **At-Least-Once** delivery. They will keep sending the message until they get a `2xx` status.
- **Problem**: If the backend crashes *after* sending the WhatsApp response but *before* committing the DB transaction (which saves the `waMessageId`), the message is effectively "Undelivered" to the DB but "Delivered" to the user.
- **Result**: On retry, the user gets a duplicate response.

## 2. Poison Message Vulnerability
### [Failure Scenario]
A malicious or malformed message triggers a `RuntimeException` (e.g., an unhandled regex edge case in `AbuseDetectionService` or a `NullPointerException` in `WhatsAppFlowService`).

1. **Webhook arrives**.
2. **Crash** before `200 OK` is sent.
3. **Meta Retries** immediately.
4. **Crash** again.
5. **Infinite Loop**: Meta will retry for up to 24 hours. The log is flooded, and the thread is constantly wasted.

### [Fix: Dead-Letter Logic]
Wrap the entire `processWebhook` logic in a top-level `try-catch` that logs the payload to a `failed_messages` table and **STILL returns 200 OK** to Meta. 
"Fail fast and acknowledge" is safer for high-volume webhooks than "Crash and retry."

## 3. Failure Matrices

| Component | Failure Mode | System Response | Recovery |
| :--- | :--- | :--- | :--- |
| **Google Gemini** | Timeout / 503 | `AI_SPIKE` Alert + Fallback to Menu | Automatic when API recovers |
| **PostgreSQL** | Connection Pool Full | Thread Wait -> 500 Error | Manual (Meta retries) |
| **WhatsApp API** | Token Expired | `[MarkRead]` Warning in logs | **Manual** (Admin must update token) |
| **Local Cache** | Memory Corruption | Potential incorrect AI answers | **Server Restart** required |

## 4. State Recovery Risks
### [Conversation Flows]
The `ConversationState` has a `lastUpdated` timestamp but no "Retry Count." 
If a user gets stuck in a flow step that always crashes, there is no automatic way to "Force Reset" the user back to the Main Menu except for the manual 24-hour timeout.

### [Recommendation]
Implement a **Step-Level Retry Limit**. If a single state update fails 3 times, automatically call `flowService.resetFlow(contact)` and notify the admin.

## 5. Circuit Breaker Analysis
The `globalGuardrailFailures` in `WhatsAppService` is a good manual implementation. 
However, it lacks **Half-Open States**. Once it hits 5 failures, it waits exactly 60s and resets to zero. A more robust implementation would slowly allow 10% of traffic through to see if the AI is healthy before fully reopening.
