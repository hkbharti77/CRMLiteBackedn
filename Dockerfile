# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder
WORKDIR /workspace

# Tune Maven JVM: tier-1 JIT stops early compilation = faster startup of mvn itself
# Not the app runtime — just the build tooling
ENV MAVEN_OPTS="-XX:+TieredCompilation -XX:TieredStopAtLevel=1 -Xmx512m -Xms256m"

# Cache Maven dependencies separately from source code
# This layer only re-runs when pom.xml changes
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f pom.xml dependency:go-offline -B -q -T 1C

COPY src ./src
# -T 1C = 1 thread per CPU core (parallel module compilation)
# Note: -o (offline) removed — dependency:go-offline misses some transitive artifacts
#       causing build failures. Cache mount already avoids redundant downloads.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f pom.xml package -Dmaven.test.skip=true -B -q -T 1C && \
    mkdir -p target/dependency && \
    cd target/dependency && \
    jar -xf ../*.jar

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
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

# JVM flags optimized for containers:
#   UseSerialGC: uses minimal memory footprint compared to G1GC
#   MaxMetaspaceSize: class metadata memory (Spring Boot 3 requires ~150MB)
#   Xss256k: thread stack size
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-Xms128m", "-Xmx350m", "-XX:+UseSerialGC", "-XX:MaxMetaspaceSize=192m", "-XX:ReservedCodeCacheSize=64m", "-Xss256k", "-XX:+ExitOnOutOfMemoryError", "-Djava.security.egd=file:/dev/./urandom", "-cp", ".:lib/*", "com.chatcrmlite.backend.ChatCrmBackendApplication"]
