# syntax=docker/dockerfile:1
# Mock judge executor: the executor service alone, without CUDA, PyTorch or nsjail. It runs with the `mock` Spring
# profile and reports verdicts chosen by `judge-mock` comments, for integration tests on machines without a GPU.
# Build context: the repository root (see compose.yaml).
FROM eclipse-temurin:26-jdk-noble AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
# warm the Gradle/plugin cache in its own layer (project dirs must exist for settings.gradle.kts)
RUN mkdir -p backend judge/judge-api judge/judge-gateway judge/judge-executor && ./gradlew --no-daemon -q help
COPY judge/judge-api judge/judge-api
COPY judge/judge-gateway judge/judge-gateway
COPY judge/judge-executor judge/judge-executor
RUN ./gradlew --no-daemon -q :judge-executor:bootJar -x test

FROM eclipse-temurin:26-jre-noble
COPY --from=build /src/judge/judge-executor/build/libs/judge-executor.jar /opt/judge/judge-executor.jar
ENV SPRING_PROFILES_ACTIVE=mock
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/judge/judge-executor.jar"]
