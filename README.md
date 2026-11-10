# GpuCodeforces

Multi-module Kotlin/Gradle project with a React frontend:

| Part | Description |
|---|---|
| `backend` | Spring Boot application |
| `judge-api`, `judge-gateway`, `judge-executor` (in `judge/`) | Testing system, see [Testing system](#testing-system) |
| `frontend` | React application (npm, not part of the Gradle build), see [frontend/README.md](frontend/README.md) |

Add future Gradle modules with `include(":module-name")` in `settings.gradle.kts`.
UML diagrams of the architecture (in Russian) are in [docs/architecture.md](docs/architecture.md).

## Requirements

- JDK 26 (also select it as the Gradle JVM in IntelliJ IDEA 2026.2).
- Docker with a running daemon for PostgreSQL and integration tests.
- Gradle is provided by the wrapper; no global installation is needed.
- `python3` for the `judge-executor` tests.
- For the GPU executor only: Linux or WSL2, NVIDIA driver 570+ and `nvidia-container-toolkit`.

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

The problem bank follows the problem repository
[lottery7/GpuCodeforcesProblems](https://github.com/lottery7/GpuCodeforcesProblems): the backend clones it
into `PROBLEMS_DIRECTORY`, pulls it every minute and imports `PROBLEMS_PATH`; without a directory the bank
is empty. The package format is described (in Russian) in
[docs/backend/problem-package.md](docs/backend/problem-package.md).

Supported formats are ordinary CUDA tasks, optimization of a supplied solution and guessing which of two
solutions is faster. Duels match a random opponent or start after accepting an invitation; the shared task is selected by topic. Package metadata, participation
and scoring are described in [docs/backend/formats.md](docs/backend/formats.md).

```sh
PROBLEMS_GIT_URL=https://github.com/lottery7/GpuCodeforcesProblems.git PROBLEMS_DIRECTORY=build/problems   PROBLEMS_PATH=problems ./gradlew :backend:bootRun
```

Submissions go to the judge gateway (`JUDGE_GATEWAY_URL`, default <http://localhost:8090>) and
verdicts come back through RabbitMQ (`RABBITMQ_HOST`), so start the judge profile as well, see
[Testing system](#testing-system). How submissions are queued and judged, how results are compared,
and the administrator Groovy console (`ADMIN_HANDLES`, page `/admin`) are described (in Russian) in
[docs/backend/submissions.md](docs/backend/submissions.md).

```sh
./gradlew test
./gradlew :backend:integrationTest # Starts and removes its own PostgreSQL container
./gradlew build                   # Builds backend and judge services and runs all tests
docker compose down              # Stops the development database, retaining data
```

Integration tests use a separate disposable database with random ports, independent
of Docker Compose. Docker must be available; these tests fail rather than silently
skip when it is unavailable. The executable jar is under `backend/build/libs/`.

CI runs both backend test tasks ([backend.yml](.github/workflows/backend.yml)). `master` is protected:
a pull request merges only after the `backend`, `frontend`, `tests` (judge) and `generated-java`
(OpenAPI) checks pass. The required checks are listed by job name in the branch protection of the
repository, so add a new CI job there too.

## Running the whole system

```sh
JUDGE_MODE=mock docker compose --profile app --profile mock up -d --wait --build   # no GPU: mock executor
docker compose --profile app --profile gpu up -d --wait --build    # real executor on an NVIDIA GPU
```

| Service | Address |
|---|---|
| Frontend | <http://localhost:3000> |
| Backend API, admin page | <http://localhost:8080/api>, <http://localhost:3000/admin> |

The backend imports the problem repository (branch `PROBLEMS_GIT_BRANCH`, default `master`) and treats users with handles from
`ADMIN_HANDLES` (default `admin`) as administrators. With the mock executor the verdict is chosen by a
comment in the solution, see [Testing system](#testing-system).

## Testing system

The judge accepts `.cu` submissions, compiles them, runs them on a GPU in an nsjail
sandbox and reports verdicts asynchronously through RabbitMQ. Architecture, problem
format, sandbox, verdicts and the backend contract are described (in Russian) in
[judge/README.md](judge/README.md) and [judge/docs/problem-format.md](judge/docs/problem-format.md).

Its services are behind Compose profiles, so plain `docker compose up -d --wait`
still starts only PostgreSQL:

```sh
docker compose --profile judge up -d --wait          # RabbitMQ + judge gateway
docker compose --profile mock up -d --wait --build   # + mock executor, no GPU needed
docker compose --profile gpu up -d --wait --build    # + real executor on an NVIDIA GPU
```

| Service | Address |
|---|---|
| Gateway API, Swagger UI | <http://localhost:8090>, `/swagger-ui.html` |
| RabbitMQ management UI | <http://localhost:15672> |
| Executor actuator (`gpu` profile) | <http://localhost:8081/actuator/health> |

The mock executor answers with the verdict requested in a source comment, for example
`// judge-mock: verdict=WRONG_ANSWER test=2`, and `ACCEPTED` otherwise. Do not run it
next to a real executor of the same GPU profile. Executors on other machines use
[judge/deploy/compose.executor.yaml](judge/deploy/compose.executor.yaml).

Send a submission from the console (`--watch` needs `pip install pika`):

```sh
python3 judge/tools/judge_cli.py submit judge/problems/examples/vector_add \
  judge/problems/examples/vector_add/solutions/accepted.cu --watch
```

The gateway API contract is [judge/docs/openapi.yaml](judge/docs/openapi.yaml). Its
controllers implement interfaces generated from it the same way as the backend's (see
below), with `:judge-gateway` tasks and the snapshot
[judge/docs/openapi-java.sha256](judge/docs/openapi-java.sha256). Schemas that are also
broker messages (`ProblemSpec`, `SubmissionRequest` and the rest) map to the `judge-api`
classes instead of generated models; a gateway test checks that they agree with the spec.

Judge tests need neither a GPU nor Docker. They are part of `./gradlew test`
and run in CI ([judge.yml](.github/workflows/judge.yml)):

```sh
./gradlew :judge-api:test :judge-gateway:test :judge-executor:test
(cd judge/pytools && python3 -m unittest discover -s tests -v)
```

## Frontend API

The React application is in [frontend](frontend/README.md).

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
