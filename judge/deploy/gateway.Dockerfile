# syntax=docker/dockerfile:1
# Judge gateway. Build context: the `judge/` directory.
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
# warm the Gradle/plugin cache in its own layer (module dirs must exist for settings.gradle.kts)
RUN mkdir -p judge-api judge-gateway judge-executor && ./gradlew --no-daemon -q help
COPY judge-api judge-api
COPY judge-gateway judge-gateway
COPY judge-executor judge-executor
RUN ./gradlew --no-daemon -q :judge-gateway:bootJar -x test

FROM eclipse-temurin:21-jre-noble
COPY --from=build /src/judge-gateway/build/libs/judge-gateway.jar /opt/judge/judge-gateway.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/judge/judge-gateway.jar"]
