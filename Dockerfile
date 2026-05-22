# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /workspace

# Cache Maven dependencies separately from source code
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f pom.xml dependency:go-offline -B -q

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f pom.xml package -DskipTests -B -q && \
    mkdir -p target/dependency && \
    cd target/dependency && \
    jar -xf ../*.jar

# ── Stage 2: Runtime (distroless for minimal attack surface) ──────────────────
FROM eclipse-temurin:17-jre-alpine AS runtime

# Security: run as non-root user
RUN addgroup -S crmlite && adduser -S crmlite -G crmlite
USER crmlite

WORKDIR /app

ARG DEPENDENCY=/workspace/target/dependency

COPY --from=builder ${DEPENDENCY}/BOOT-INF/lib     ./lib
COPY --from=builder ${DEPENDENCY}/META-INF          ./META-INF
COPY --from=builder ${DEPENDENCY}/BOOT-INF/classes  .

# Health check (hits actuator liveness endpoint)
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness | grep -q '"status":"UP"' || exit 1

EXPOSE 8080

# JVM flags optimized for containers:
#   UseContainerSupport: respects cgroup memory limits
#   MaxRAMPercentage:    use up to 75% of container RAM for heap
#   G1GC:               low-latency GC for web apps
#   ExitOnOutOfMemoryError: crash fast instead of limping
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseG1GC", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:prod}", \
  "-cp", ".:lib/*", \
  "com.chatcrmlite.backend.BackendApplication"]
