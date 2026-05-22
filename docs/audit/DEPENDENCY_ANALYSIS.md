# Dependency Analysis — CRMLite Backend

## 1. Maven Dependencies Overview
The project uses a well-curated set of modern dependencies. However, some risks are present.

## 2. Security Risks
- **Hardcoded Versioning**: Some versions are hardcoded instead of using properties/BOM, which makes management difficult.
- **Transitive Vulnerabilities**: Since `pom.xml` uses many "starter" dependencies, there is a risk of inherited CVEs in underlying libraries (e.g., Netty, Log4j if not updated).

## 3. Outdated/Vulnerable Packages
- **LangChain4j**: The version used (0.35.0) is stable, but AI libraries evolve weekly. A regular update cycle is needed for new model support.
- **Spring Boot 3.4.0**: Very recent and secure.

## 4. Heavy Dependencies
- **ONNX Runtime / Embedding Models**: The `langchain4j-embeddings-all-minilm-l6-v2-q` dependency includes a quantized model (~25MB) and the ONNX runtime. This significantly increases the JAR size and startup memory footprint (~200MB+ overhead just for AI initialization).

## 5. Redundant Libraries
- **Custom JSON Utils**: There are instances where manual JSON string manipulation is used (e.g., `enquiries` in `Lead.java`) despite having a full Jackson-based `ObjectMapper` available.
- **In-Memory Tracking**: `RagController` uses a `HashMap` for task tracking instead of a proper queue or database table, which is a structural debt.

## 6. Dependency Conflicts
- **Jakarta EE vs J2EE**: Being on Spring Boot 3, the project correctly uses Jakarta EE. No major namespace conflicts were detected.
- **Netty**: Used by both Spring WebFlux (if present) and potentially WhatsApp clients. Version alignment is critical to avoid `NoSuchMethodError`.

## 7. Recommended Improvements
- **Move to BOM**: Use `langchain4j-bom` to manage all LangChain4j versions.
- **Prune Unused Starters**: Ensure `spring-boot-starter-webflux` is not present if only using `spring-boot-starter-web` (blocking).
