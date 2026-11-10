# GpuCodeforces

Платформа соревнований по CUDA: React-клиент и backend на Kotlin/Spring Boot,
с хранением данных в PostgreSQL через JDBC и миграциями Flyway.

Реализованы все 20 операций клиентского API: аккаунты и сессии, задачи,
посылки, соревнования, регистрация, таблицы результатов и рейтинг.
Задачи импортируются из `master` репозиториев, доступных GitHub App.
Корневой `index.json` перечисляет несколько задач и пути к их манифестам.
Сохраняются неизменяемые версии условий, генераторов и эталонов.
Соревнования и посылки ссылаются на конкретные версии задач.

Очередь посылок и буфер результатов хранятся в БД. Backend назначает задания
внешним тестировщикам, ограничивает число одновременных посылок, обрабатывает
результаты отдельных тестов в фоне, переназначает работу после таймаута
и подтверждает живость тестировщиков по ответам о посылках и обратному pong.
Сами тестировщики в проект не входят. Демонстрационный сервер frontend
остаётся отдельным инструментом и прикладным backend не используется.

На `/admin` доступны Groovy-консоль с контекстом приложения `ctx` и настройки
без перезапуска. Админские права хранятся в БД; первого администратора назначают
одноразовым секретом `ADMIN_BOOTSTRAP_TOKEN`. Порядок настройки описан в документации backend.

**Настройка GitHub App, формат репозитория задачи, ограничения ресурсов,
настройка тестировщиков и текущие ограничения:** [backend](docs/backend/README.md).
**Сетевой протокол тестировщиков:** [judge-protocol.md](docs/backend/judge-protocol.md).

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
SECURE_COOKIES=false ./gradlew :backend:bootRun
```

The application listens on port 8080. Health: <http://localhost:8080/actuator/health>.
Override `DATABASE_URL`, `DATABASE_USER`, and `DATABASE_PASSWORD` to use another
PostgreSQL instance. The default credentials are for local development.
Flyway manages migrations in `backend/src/main/resources/db/migration`;
Hibernate validates the schema rather than modifying it.

PostgreSQL integration tests automatically check application query plans for unexpected
full scans. Intentional scans have explicit policies and justifications:
[query plan checks](docs/backend/query-plans.md).

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

The React application is in [frontend](frontend/README.md).

The contract is [docs/frontend/openapi.yaml](docs/frontend/openapi.yaml).
It covers the MVP screens and actions, including example checks and retrying failed
checks. Password recovery, private problems and GPU selection remain outside the MVP.

```sh
./gradlew :backend:openApiValidate :backend:openApiGenerate
```

Java Spring interfaces and models are generated in `backend/build/generated/openapi`
and included in compilation automatically. Application controllers implement the
HTTP contract independently of generated Java interfaces. Generated sources are not committed.

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
