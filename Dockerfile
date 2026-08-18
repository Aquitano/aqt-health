# Build stage
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle/wrapper/ gradle/wrapper/

RUN chmod +x ./gradlew

# Optional: warm Gradle dependency cache
RUN ./gradlew dependencies --no-daemon || true

COPY src/ src/

RUN ./gradlew shadowJar \
    -x test \
    -x integrationTest \
    -x generateOpenApi \
    --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl \
    && addgroup -S appgroup \
    && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=builder /app/build/libs/*-all.jar app.jar

# The JSONL appender is always wired up; give it a directory appuser can write to.
RUN mkdir logs && chown appuser:appgroup logs

USER appuser

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0" \
    AQT_HEALTH_LOG_FILE="/app/logs/aqt-health.jsonl" \
    AQT_HEALTH_LOG_FILE_ROLLOVER="/app/logs/aqt-health.%d{yyyy-MM-dd}.%i.jsonl.gz"

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/api/v2/admin/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
