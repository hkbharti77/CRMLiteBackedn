# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder
WORKDIR /workspace

# Maven heap tuned for 2GB EC2: 768m prevents GC thrashing without hitting OOM
ENV MAVEN_OPTS="-XX:+TieredCompilation -XX:TieredStopAtLevel=1 -Xmx768m -Xms256m"

# Cache Maven dependencies separately from source code
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f pom.xml dependency:go-offline -B -q

COPY src ./src
# Build fat JAR without tests; avoid unpacking thousands of files to save EBS I/O
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f pom.xml package -Dmaven.test.skip=true -DskipTests -B -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy AS runtime

# Security: run as non-root user
RUN addgroup --system crmlite && adduser --system --ingroup crmlite crmlite
USER crmlite

WORKDIR /app

# Copy the single compiled JAR (saves 2+ minutes of layer exporting on EBS)
COPY --from=builder /workspace/target/*.jar ./app.jar

EXPOSE 8080 50000-50100/udp

# JVM flags optimized for 2GB total RAM budget (EC2 t3.small / 2GB):
# Heap (900m) + Metaspace (256m) + CodeCache (96m) = ~1.3GB safe footprint with G1GC
ENV JAVA_TOOL_OPTIONS="-XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=96m -Xms256m -Xmx900m -XX:+UseG1GC"

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-Xss512k", "-XX:+ExitOnOutOfMemoryError", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
