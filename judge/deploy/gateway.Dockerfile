# syntax=docker/dockerfile:1
# Judge gateway. Build context: the repository root (see compose.yaml).
# base images are pinned by digest: a tag can be moved
FROM eclipse-temurin:26-jdk-noble@sha256:9c0e8eddecccc0d5679b317b0c90fa7103ad25cae33df1c00280675eb681c7f8 AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
# warm the Gradle/plugin cache in its own layer (project dirs must exist for settings.gradle.kts)
RUN mkdir -p backend judge/judge-api judge/judge-gateway judge/judge-executor && ./gradlew --no-daemon -q help
COPY judge/judge-api judge/judge-api
COPY judge/judge-gateway judge/judge-gateway
COPY judge/judge-executor judge/judge-executor
RUN ./gradlew --no-daemon -q :judge-gateway:bootJar -x test

FROM eclipse-temurin:26-jre-noble@sha256:adfd44c114916dc79fa9a24087fbac34252096edb9892d1c2d9d82db3f8947b3
COPY --from=build /src/judge/judge-gateway/build/libs/judge-gateway.jar /opt/judge/judge-gateway.jar
EXPOSE 8090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/judge/judge-gateway.jar"]
