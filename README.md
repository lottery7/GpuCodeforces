# GpuCodeforces

Multi-module Kotlin/Gradle project. `backend` is a Spring Boot application;
add future modules with `include(":module-name")` in `settings.gradle.kts`.

## Requirements

- JDK 26 (also select it as the Gradle JVM in IntelliJ IDEA 2026.2).
- Docker with a running daemon for PostgreSQL and integration tests.
- Gradle is provided by the wrapper; no global installation is needed.

Versions: Kotlin 2.4.21, Gradle 9.7.0, Spring Boot 4.1.2.
Spring Boot's BOM manages the backend libraries, including Hibernate,
Flyway, Jackson, PostgreSQL JDBC, JUnit, and Testcontainers.

## Local development

```sh
docker compose up -d --wait
./gradlew :backend:bootRun
```

The application listens on port 8080. Health: <http://localhost:8080/actuator/health>.
Override `DATABASE_URL`, `DATABASE_USER`, and `DATABASE_PASSWORD` to use another
PostgreSQL instance. The default credentials are for local development.
Flyway manages migrations in `backend/src/main/resources/db/migration`;
Hibernate validates the schema rather than modifying it.

```sh
./gradlew test
./gradlew :backend:integrationTest # Starts and removes its own PostgreSQL container
./gradlew build                   # Builds the application and runs all tests
docker compose down              # Stops the development database, retaining data
```

Integration tests use a separate disposable database with random ports, independent
of Docker Compose. Docker must be available; these tests fail rather than silently
skip when it is unavailable. The executable jar is under `backend/build/libs/`.

## Frontend API

The contract is [docs/frontend/openapi.yaml](docs/frontend/openapi.yaml).
It covers the MVP screens and actions, including example checks and retrying failed
checks. Password recovery, private problems and GPU selection remain outside the MVP.

```sh
./gradlew :backend:openApiValidate :backend:openApiGenerate
```

Java Spring interfaces and models are generated in `backend/build/generated/openapi`
and included in compilation automatically. Implement the interfaces in backend
controllers. Generated sources are not committed.

CI regenerates Java and compares all file paths and SHA256 checksums with
[openapi-java.sha256](docs/frontend/openapi-java.sha256):

```sh
./gradlew :backend:verifyOpenApiGenerated --rerun-tasks --no-build-cache
```

After an intentional contract or generator change, update the snapshot and commit
it together with the change:

```sh
./gradlew :backend:updateOpenApiSnapshot
```
