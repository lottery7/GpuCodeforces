# GPU Programming Challenge Platform — Backend

A Kotlin + Spring Boot backend for a Codeforces-like platform that hosts **GPU
programming problems**. This project implements the **problem catalog** and its
**Git-based content synchronization**. It does **not** (yet) implement code
submission, compilation, GPU execution, judging or leaderboards.

The database is a queryable projection of a **problem content Git repository**:
problems are defined as Markdown files with YAML front matter, imported on
demand and exposed through a versioned REST API.

---

## What's included

- Problem catalog persisted in PostgreSQL (Flyway migrations, JSONB metadata).
- Import of `problems/<slug>/problem.md` files from a configured Git
  repository/branch using JGit (clone → fetch → checkout → parse → upsert).
- Deterministic SHA-256 content hashing so unchanged files are skipped.
- Public REST API (`/api/v1/problems`) with pagination, tag filtering and sorting.
- Internal sync endpoints (`/api/internal/problems/sync[+/status]`).
- GitHub push webhook with HMAC-SHA256 signature verification.
- OpenAPI / Swagger UI.
- Unit + integration tests (Testcontainers).

## What's intentionally out of scope (not implemented)

Frontend, user accounts/auth, submissions, CPU/GPU compilation, CUDA execution,
sandboxing, judging, leaderboards, contests, problem version history, tag
management UI, automatic deletion of removed problems, and multiple GPU targets.
The problem repository carries the canonical `solution.py` and `tests.py`
scripts, but actually **running** them (compiling/solving/judging) is not
implemented — the platform imports and serves them only.

Example format for the scripts:

```python
# solution.py — plausible canonical solution
def solve(inputs):
    a = inputs["A"].cuda()
    b = inputs["B"].cuda()
    return {"C": a + b}
```

```python
# tests.py — the test suite is generated on-the-fly from a seed
from collections.abc import Iterator
import numpy as np

def generate_tests(seed: int) -> Iterator[dict]:
    rng = np.random.default_rng(seed)
    n = 1024
    yield {"inputs": {"A": rng.random(n, dtype=np.float32),
                      "B": rng.random(n, dtype=np.float32),
                      "N": np.array(n, dtype=np.int32)}}
```

`solve(inputs: dict) -> dict` receives the generated `inputs` (e.g. as CUDA
tensors when running on the GPU) and returns the outputs. `generate_tests` is an
iterator that yields zero or more test cases per seed.

---

## Architecture

Straightforward layered Spring Boot application under
`com.example.gpuplatform`:

```
config          Jackson / OpenAPI / problems properties
problem/
  api           public controllers + DTOs
  domain        Problem entity, ProblemMetadata (typed)
  repository    ProblemRepository (incl. jsonb tag filtering)
  service       ProblemService (list/detail)
sync/
  api           ProblemSyncController, GitHubWebhookController, internal-key interceptor
  domain        SyncState, ParsedProblem, Slugs, ProblemSource
  repository    SyncStateRepository + entity (single-row sync state)
  service       GitRepositoryService, ProblemParser, ProblemImportService,
                ProblemSyncService, AsyncSyncTrigger, GitHubWebhookVerifier
common/api      PageResponse, ApiException, GlobalExceptionHandler
```

Flow of a sync:

1. `ProblemSyncService.triggerSync()` marks the state `RUNNING`.
2. `GitRepositoryService.obtainSnapshot()` clones (or fetches) the configured
   repo/branch and returns the commit SHA + discovered `problems/*/problem.md`
   files.
3. `ProblemImportService.importAll()` reads each problem's `problem.md` and
   its optional sibling `solution.py`/`tests.py`, parses it (`ProblemParser`),
   computes a SHA-256 content hash over all three files, and **upserts by slug**
   inside one transaction, preserving the database `id`. A change to any of the
   three files invalidates the hash, re-imports the row and advances its
   `gitHash`.
4. The sync state is updated to `SUCCESS`/`FAILED` with counts.

Problems are never deleted automatically; removal of a source file is currently
ignored.

---

## Prerequisites

- JDK 21
- Docker + Docker Compose (for local PostgreSQL and integration tests)
- (Gradle wrapper is included — no global Gradle needed)

---

## Required environment variables

All variables have defaults that work for local development; copy
`.env.example` and adjust as needed. The important ones:

| Variable | Description | Default |
|---|---|---|
| `DB_URL` | JDBC URL | `jdbc:postgresql://localhost:5432/gpuproblems` |
| `DB_USERNAME` | DB user | `gpu` |
| `DB_PASSWORD` | DB password | `gpu` |
| `PORT` | HTTP port | `8080` |
| `PROBLEMS_GIT_URL` | Git URL of the problems content repo (**required for sync**) | empty |
| `PROBLEMS_GIT_BRANCH` | Branch to sync | `main` |
| `PROBLEMS_GIT_DIRECTORY` | Local clone directory | `./data/problems-repo` |
| `PROBLEMS_DIRECTORY` | Sub-directory inside the repo holding `problems/` | `problems` |
| `PROBLEMS_SYNC_ON_STARTUP` | Run a sync at startup | `false` |
| `INTERNAL_API_KEY` | Optional shared key for `/api/internal/**`; empty = no check | empty |
| `GITHUB_WEBHOOK_SECRET` | Secret for verifying webhook signatures; empty = webhook disabled | empty |

---

## Start PostgreSQL locally

```bash
docker compose up -d
```

This starts `postgres:16-alpine` on `localhost:5432` with database `gpuproblems`
(user/password `gpu`/`gpu`). Stop it with `docker compose down`.

> Note: `docker-compose.yml` pins an explicit Docker network subnet
> (`172.28.0.0/16`) to avoid conflicts on hosts that exhaust `docker`'s default
> bridge address pools.

---

## Configure the problems Git repository

Point `PROBLEMS_GIT_URL` at your content repo. It must follow this layout:

```
gpu-problems/
└── problems/
    └── vector-addition/
        ├── problem.md
        ├── solution.py
        └── tests.py
```

The **slug is the directory name**. `solution.py` and `tests.py` are optional;
when present they are stored and served through the detail endpoint. Add example
content by committing the files under `sample-problems/` at the repo root, or
push your own.

A quick way to try it with a local repo:

```bash
git init -b main /tmp/problems-repo
cp -r sample-problems /tmp/problems-repo/
git -C /tmp/problems-repo add -A
git -C /tmp/problems-repo -c user.name=dev -c user.email=dev@x.com commit -m init
```

Then run the app with:

```bash
export PROBLEMS_GIT_URL=file:///tmp/problems-repo
export INTERNAL_API_KEY=dev-key
export GITHUB_WEBHOOK_SECRET=dev-secret
./gradlew bootRun
```

---

## Run the application

With PostgreSQL up through Docker Compose:

```bash
export PROBLEMS_GIT_URL=file:///path/to/problems-repo   # required to sync
./gradlew bootRun
```

Or build a Jar and run it:

```bash
./gradlew bootJar
java -jar build/libs/gpu-platform-0.1.0.jar
```

## Run the whole stack with Docker Compose

`docker-compose.yml` now defines both `postgres` and `service`. The `service`
container is built from the multi-stage `Dockerfile` (`gradle:8.14.3-jdk21`
build stage -> `eclipse-temurin:21-jre` runtime) and exposes the app on port
`8080`. Its sync directory is a named volume (`gpuproblems-repo`) mounted at
`/data` inside the container.

Optional app configuration (e.g. `PROBLEMS_GIT_URL`, `PROBLEMS_GIT_BRANCH`,
`INTERNAL_API_KEY`, `GITHUB_WEBHOOK_SECRET`, `PORT`) is read from a local
`.env` file if present (see `.env.example`). `DB_*` defaults already point the
service at the `postgres` service over the Docker network.

```bash
cp .env.example .env          # edit as needed; .env is git-ignored
docker compose up -d --build
```

The service waits for Postgres to become healthy, then starts. Stop everything
with `docker compose down` (add `-v` to also drop the data/sync volumes).

> Note: because the service runs in a container, a Git URL such as
> `file:///tmp/problems-repo` will not resolve to the host path — use a host's
> reachable URL (an SSH/HTTPS repo, or the host IP) for syncing the DB in the
> service container.

---

## Access Swagger UI

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs

## Generate the OpenAPI document without starting the server

springdoc builds the spec from the Spring context, so you don't need a running
HTTP server. The `OpenApiSpecTest` boots the full context (with the shared
Testcontainers Postgres), fetches `/v3/api-docs` in-memory via MockMvc and
persists the result:

```bash
./gradlew test --tests "*OpenApiSpecTest*"
```

This writes `build/openapi/openapi.json` (OpenAPI 3.0.1). Note that it still
starts a Postgres container, just not the application server.

---

## Trigger a manual sync

```bash
curl -X POST http://localhost:8080/api/internal/problems/sync \
  -H "X-Internal-Key: dev-key"          # only if INTERNAL_API_KEY is set
```

Inspect the latest sync state:

```bash
curl http://localhost:8080/api/internal/problems/sync/status \
  -H "X-Internal-Key: dev-key"
```

These are **admin/internal endpoints**. There is no full auth system; protect
them at the deployment/network layer, and optionally set `INTERNAL_API_KEY` to
require a shared secret in the `X-Internal-Key` header.

---

## Configure the GitHub webhook

Set `GITHUB_WEBHOOK_SECRET` to a shared secret. Then add a webhook in the repo
settings pointing to:

```
POST http://<host>/api/webhooks/github
```

with content type `application/json`. The backend verifies
`X-Hub-Signature-256` against the configured secret, only reacts to `push`
events on the configured branch, and triggers a **background** sync. It ignores
the repository URL/branch from the payload — the server configuration is always
authoritative.

Response codes: `202` sync triggered · `200` ignored (non-push / other branch)
· `401` invalid signature · `503` webhook not configured (no secret).

---

## Public API

### List problems

```
GET /api/v1/problems?page=0&size=20&tag=EASY&sortBy=title&sortDir=ASC
```

Query parameters: `page` (0-based), `size` (1–100), `tag` (optional), `sortBy`
(`title` | `createdAt` | `updatedAt`), `sortDir` (`ASC`|`DESC`).

Response is a pagination envelope:

```json
{
  "content": [
    { "slug": "vector-addition", "title": "Vector Addition",
      "tags": ["EASY", "vector", "arithmetic"], "author": "platform",
      "gitHash": "9f3a...f1c" }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
}
```

### Get problem details

```
GET /api/v1/problems/{slug}
```

Returns slug, title, tags, author, `gitHash`, the raw Markdown `statement`, the
`solutionPy` / `testsPy` script sources (when present), and `metadata`
(`interface`, `constraints`, `execution`). Returns `404` for an unknown slug.

> `gitHash` is the Git commit SHA that last imported or updated this problem's
> content. It is a per-problem "last modified" marker, so a client can detect
> that a given task changed by comparing `gitHash` across requests — when the
> content of a problem changes, its `gitHash` changes. Unchanged problems keep
> their previous `gitHash`, so it is **not** a catalog-wide sync version.

---

## Creating a new problem

Add a directory under the repo's `problems/` tree. Every problem consists of a
`problem.md` plus **two optional Python scripts**:

```
problems/vector-addition/
├── problem.md
├── solution.py      # def solve(inputs: dict) -> dict
└── tests.py         # def generate_tests(seed: int) -> Iterator[dict]
```

The Markdown file carries the human-readable statement and structured metadata in
YAML front matter:

```markdown
---
title: Vector Addition
tags:
  - EASY
  - vector
author: platform

interface:
  function_name: solve
  parameters:
    - name: A
      type: float*
      direction: input

constraints:
  N:
    min: 1
    max: 10000000

execution:
  gpu:
    min_compute_capability: "7.0"
  limits:
    gpu_time_ms: 1000
---

# Vector Addition

Given two input vectors A and B, calculate:

C[i] = A[i] + B[i]
```

### Supported front matter

- `title` — **required**.
- `tags` — list of strings. Difficulty is a tag (`EASY`/`MEDIUM`/`HARD`); there
  is no separate difficulty or category field.
- `author` — optional.
- `interface` — structured (`function_name`, `parameters[]` with `name`,
  `type`, `direction`).
- `constraints` — map of variable name → `{ min, max }`.
- `execution` — structured (`gpu`, `limits` with `min_compute_capability`,
  `min_memory_mb`, `gpu_time_ms`, `host_memory_mb`, `device_memory_mb`).
- No publication-status field in metadata; any publication state belongs to the
  backend, not the YAML. Unknown keys are tolerated for extensibility.

The parser validates required fields, tolerates unknown keys for extensibility,
handles CRLF and LF, and reports the full source path on errors. The raw Markdown
body is stored as-is (not rendered). The `solution.py` / `tests.py` sources are
stored verbatim and returned through the detail endpoint; a change to any of the
three files re-imports the problem and advances its `gitHash`.

---

## Tests

```bash
./gradlew test
```

- **Unit tests**: front matter parsing, Markdown body extraction, malformed/missing
  front matter, metadata validation, script passthrough, slug derivation,
  composite content-hash stability, webhook signature verification.
- **Integration tests** (Testcontainers PostgreSQL): Flyway migrations, insert,
  update-by-slug preservation of id, unchanged-skip, listing/pagination, tag
  filtering, fetch-by-slug, script persistence + script-only-change re-import,
  404, full sync from a local Git repo, sync-failure tracking,
  webhook endpoint responses.

---

## Project layout

```
docker-compose.yml       local PostgreSQL + service stack
Dockerfile               multi-stage image for the service
.dockerignore
.env.example             documented environment variables
sample-problems/         example problems incl. solution.py / tests.py
src/main/
  kotlin/...             application source
  resources/application.yml
  resources/db/migration/V1__init_schema.sql, V2__add_problem_scripts.sql
src/test/kotlin/...      tests
```

## Remaining limitations

- No submission, execution, sandbox, judging or leaderboard functionality.
- The `solution.py` / `tests.py` scripts are imported and served through the API,
  but the platform does not run them (no solver/compiler/sandbox pipeline yet).
- Git sync is full (fetch + re-scan) on every trigger — no incremental diff.
- Removed problem files do not delete database rows (out of scope).
- Internal endpoints rely on an optional shared secret or network-layer
  protection; there is no full auth system.