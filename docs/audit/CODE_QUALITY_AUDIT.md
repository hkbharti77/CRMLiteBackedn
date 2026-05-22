# Code Quality Audit — CRMLite Backend

## 1. Complexity & Readability
- **God Classes**: `WhatsAppService` and `WhatsAppFlowService` suffer from extreme cyclomatic complexity.
- **Deep Nesting**: The `advanceFlow` and `processWebhook` methods have multiple levels of `if/else` and `switch` statements, making them hard to test and maintain.
- **Naming**: Generally good and expressive (e.g., `handleDynamicServiceList`, `isInteractiveSelection`), following Java conventions.

## 2. Testability
- **Current State**: **VERY LOW**. No unit or integration tests were observed in the project root.
- **Barrier**: The tight coupling between services makes it nearly impossible to write unit tests without extensive mocking. 
- **Refactor Goal**: Decouple logic from `WhatsAppService` into pure functions/smaller services that can be tested in isolation.

## 3. Error Handling & Exceptions
- **Global Handler**: The project uses a `GlobalExceptionHandler` to prevent leaking stack traces to the API. This is good.
- **Flow Errors**: Errors within the WhatsApp flow often result in a generic "Invalid selection" or no response at all, which is a poor user experience.
- **Exception Flow**: Some methods return `null` or `false` on failure instead of throwing typed exceptions (e.g., `RagRetrievalService.getAiResponse`), leading to potential `NullPointerException` upstream.

## 4. Reusability & DRY
- **JSON Parsing**: The project re-implements `ObjectMapper.readValue` in multiple services instead of having a central `JsonUtils` or reusing a bean.
- **Magic Strings**: Hardcoded strings like `"btn_trust"`, `"flow_page_"`, and `"srv_"` are scattered across services and handlers. These should be centralized in an `Constants` or `FlowType` enum.

## 5. Logging Quality
- **Verbosity**: Logs are informative (e.g., `[Flow] Started 'ENQUIRY' flow...`).
- **Standardization**: Missing a standard MDC (Mapped Diagnostic Context) to track a single user's request across multiple services.
- **Privacy**: No evidence of PII scrubbing. Customer phone numbers and names are logged in plain text.

## 6. Dead Code & Technical Debt
- **Stubs**: `AuthController.logout` and some placeholder DTOs.
- **In-Memory Maps**: `userSessions` and `aiHitsPerMinute` in `RagGuardrailService` should eventually move to Redis to survive restarts.
