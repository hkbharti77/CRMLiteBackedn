# Technology Stack — CRMLite Backend

## 1. Core Frameworks
- **Spring Boot 3.4.0**: Latest stable version, leveraging Java 17 features.
- **Spring Security**: Stateless JWT-based authentication.
- **Spring Data JPA**: Hibernate-based persistence.

## 2. Libraries
- **Lombok**: Reduced boilerplate.
- **Jackson**: JSON serialization/deserialization.
- **Resilience4j**: Circuit breakers (specifically for Gemini LLM calls).
- **Bucket4j**: Rate limiting (API-wide and per-tenant).
- **Caffeine**: High-performance in-memory caching for vector stores and metadata.
- **Flyway**: Database migration management.

## 3. Databases
- **PostgreSQL**: Primary relational store.
- **In-Memory Store (Custom)**: Caffeine-backed `InMemoryEmbeddingStore` for tenant-specific vector retrieval.

## 4. AI/LLM Dependencies
- **LangChain4j**: The primary bridge for AI.
- **Google Gemini (Gemini Pro/Flash)**: The LLM provider.
- **AllMiniLmL6V2Quantized**: Local embedding model for vector generation (ONNX-based).

## 5. Messaging & Integration
- **WhatsApp Cloud API (Meta)**: Direct integration for messaging.
- **SMTP**: Java Mail for system and tenant notifications.

## 6. Infrastructure & Deployment (Observed)
- **ngrok**: Used for exposing webhooks during local development.
- **Docker**: (Presumed) for production packaging.

## 7. Security Libraries
- **jjwt**: Java JWT library for token signing and validation.
- **Bcrypt**: Password hashing (though primarily passwordless OTP is used).
