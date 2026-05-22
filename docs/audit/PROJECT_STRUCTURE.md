# Project Structure Analysis — CRMLite Backend

## 1. Full Folder Structure
The project follows a standard Spring Boot Maven multi-layered monolith structure:

```text
CRMLiteBackedn/
├── src/
│   ├── main/
│   │   ├── java/com/chatcrmlite/backend/
│   │   │   ├── clients/          # External API Clients (WhatsApp, Gemini)
│   │   │   ├── config/           # Infrastructure & Security Config
│   │   │   ├── controllers/      # REST Endpoints
│   │   │   ├── dto/              # Data Transfer Objects
│   │   │   ├── event/            # Application Events & Listeners
│   │   │   ├── exceptions/       # Custom Exception Handling
│   │   │   ├── flow/             # Conversational Flow Handlers
│   │   │   ├── models/           # JPA Entities
│   │   │   ├── repositories/     # Data Access Layer
│   │   │   ├── security/         # JWT & Auth Filters
│   │   │   └── services/         # Business Logic (Fat Services)
│   │   └── resources/
│   │       ├── db/migration/     # Flyway Migrations
│   │       ├── guardrails/       # AI Niche-specific JSON configs
│   │       └── application.properties
├── pom.xml
└── ...
```

## 2. Module Boundaries
The application is a **pure monolith**. There are no internal Maven modules. 
- **Logical boundaries** are defined by packages, but they are weakly enforced.
- **Data boundaries** are strictly enforced by a tenant-id (Owner) column across almost all tables.

## 3. Monolith vs Modular Analysis
The current monolith approach is suitable for the current scale (MVP), but as features like the "Flow Engine" and "AI Pipeline" grow, they will compete for resources.
- **Strengths**: Simple deployment, transactional integrity across domains (Lead + WhatsApp + Email).
- **Weaknesses**: Scalability is all-or-nothing. A spike in AI requests could throttle the entire Lead Management API.

## 4. Dependency Coupling Observations
- **Tight Coupling**: `WhatsAppService` is heavily coupled with almost every other service (`LeadService`, `AppointmentService`, `RagRetrievalService`, etc.).
- **Strategy Pattern Usage**: Good use of the strategy pattern in `WhatsAppFlowService` to decouple specific flow logic into `FlowHandler` implementations.
- **Event-Driven Decoupling**: Application events are used for non-blocking actions like email notifications, which is a positive architectural choice.

## 5. Circular Dependency Risks
- Potential risks exist in the `services` package due to many services injecting each other. 
- `WhatsAppService` <-> `WhatsAppFlowService` is a likely candidate for circularity if not carefully managed.

## 6. Layering Violations
- **Controller Logic**: Some controllers contain business logic (e.g., `LeadController` formatting relative time).
- **Service-to-Service Dependency**: Very high. Services often call other services to perform CRUD instead of using repositories directly or sharing a common logic layer.

## 7. Large/God Classes Detection
- **WhatsAppService.java**: ~1,500 lines. This is the definition of a God Class. It handles webhook parsing, flow routing, AI intake, manual message sending, and status updates.
- **WhatsAppFlowService.java**: Handles both flow orchestration and dynamic list generation, becoming a secondary God Object.
