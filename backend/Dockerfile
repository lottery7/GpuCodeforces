# syntax=docker/dockerfile:1
# Backend monolith. Build context: the repository root (see compose.yaml).
# base images are pinned by digest: a tag can be moved
FROM eclipse-temurin:26-jdk-noble@sha256:9c0e8eddecccc0d5679b317b0c90fa7103ad25cae33df1c00280675eb681c7f8 AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
# warm the Gradle/plugin cache in its own layer (project dirs must exist for settings.gradle.kts)
RUN mkdir -p backend judge/judge-api judge/judge-gateway judge/judge-executor && ./gradlew --no-daemon -q help
COPY docs/frontend docs/frontend
COPY judge/judge-api judge/judge-api
COPY backend backend
RUN ./gradlew --no-daemon -q :backend:bootJar -x test

FROM eclipse-temurin:26-jre-noble@sha256:adfd44c114916dc79fa9a24087fbac34252096edb9892d1c2d9d82db3f8947b3
COPY --from=build /src/backend/build/libs/backend.jar /opt/backend/backend.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/backend/backend.jar"]
