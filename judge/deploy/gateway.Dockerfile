# syntax=docker/dockerfile:1
# Judge gateway. Build context: the repository root (see compose.yaml).
FROM eclipse-temurin:26-jdk-noble AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
# warm the Gradle/plugin cache in its own layer (project dirs must exist for settings.gradle.kts)
RUN mkdir -p backend judge/judge-api judge/judge-gateway judge/judge-executor && ./gradlew --no-daemon -q help
COPY judge/judge-api judge/judge-api
COPY judge/judge-gateway judge/judge-gateway
COPY judge/judge-executor judge/judge-executor
RUN ./gradlew --no-daemon -q :judge-gateway:bootJar -x test

FROM eclipse-temurin:26-jre-noble
COPY --from=build /src/judge/judge-gateway/build/libs/judge-gateway.jar /opt/judge/judge-gateway.jar
EXPOSE 8090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/judge/judge-gateway.jar"]
