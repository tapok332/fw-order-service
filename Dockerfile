FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /workspace

# Build fw-common first
COPY --from=fw-common . /workspace/fw-common/
WORKDIR /workspace/fw-common
RUN chmod +x ./gradlew && ./gradlew build -x test --no-daemon

# Build service
WORKDIR /workspace/service
COPY --from=service . /workspace/service/
RUN chmod +x ./gradlew && ./gradlew build -x test --no-daemon

FROM eclipse-temurin:25-jre-alpine AS runtime

WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder --chown=app:app /workspace/service/build/libs/*.jar app.jar
USER app

ENTRYPOINT ["java", "-jar", "app.jar"]
