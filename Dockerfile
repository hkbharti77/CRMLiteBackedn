# ── Stage 1: Builds ────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder
WORKDIR /workspace

# Cache Maven dependencies separately from source code
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f pom.xml dependency:go-offline -B -q

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f pom.xml package -Dmaven.test.skip=true -B -q && \
    mkdir -p target/dependency && \
    cd target/dependency && \
    jar -xf ../*.jar

# ── Stage 2: Runtime (distroless for minimal attack surface) ──────────────────
FROM eclipse-temurin:17-jre-jammy AS runtime

# Security: run as non-root user
RUN addgroup --system crmlite && adduser --system --ingroup crmlite crmlite
USER crmlite

WORKDIR /app

ARG DEPENDENCY=/workspace/target/dependency

COPY --from=builder ${DEPENDENCY}/BOOT-INF/lib     ./lib
COPY --from=builder ${DEPENDENCY}/META-INF          ./META-INF
COPY --from=builder ${DEPENDENCY}/BOOT-INF/classes  .

# Removed Docker HEALTHCHECK because Render does its own TCP health checks,
# and Docker's healthcheck was killing the container before it could finish booting on 0.1 CPU.

EXPOSE 8080

# JVM flags optimized for 512MB containers:
#   UseSerialGC: uses minimal memory footprint compared to G1GC
#   MaxMetaspaceSize: caps class metadata memory
#   Xss256k: reduces thread stack size
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=60.0", \
  "-XX:+UseSerialGC", \
  "-XX:MaxMetaspaceSize=128m", \
  "-Xss256k", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:prod}", \
  "-cp", ".:lib/*", \
  "com.chatcrmlite.backend.ChatCrmBackendApplication"]
