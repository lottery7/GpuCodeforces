# Тестирующая система (judge)

Часть 3 проекта GPU Codeforces. Принимает посылки (`.cu`), компилирует их, запускает на GPU в песочнице, проверяет ответ с допуском для float и асинхронно отдаёт вердикт бэкенду.

```
Backend ──POST /api/v1/submissions──▶ judge-gateway ──▶ RabbitMQ: judge.jobs.<gpu-profile> (quorum)
        ◀──────── 202 Accepted                                 │  competing consumers (pull)
                                                               ▼
                                              judge-executor × N (одна машина = один executor,
                                              по слоту на GPU + предвыборка для компиляции)
                                                               │
Backend ◀── RabbitMQ: judge.events (QUEUED → COMPILING → PREPARING → TESTING k/n → FINISHED) ──┘
```

| Модуль | Что делает |
|---|---|
| `judge-api` | Контракты (Kotlin data classes): `SubmissionRequest`, `ProblemSpec`, `JudgeEvent`, вердикты, имена очередей. Бэкенд может подключить модуль как зависимость. |
| `judge-gateway` | Spring Boot. Валидирует посылку, кладёт задание в очередь нужного GPU-профиля (с publisher confirm), сразу отвечает 202. Дедупликация по `submissionId`, отмена, мониторинг executors и очередей. OpenAPI: `/swagger-ui.html`. |
| `judge-executor` | Spring Boot на GPU-машине. Забирает задания из очереди, компилирует, готовит тесты, запускает решение в nsjail на свободной GPU, проверяет, публикует события. |
| `harness/` | C++/CUDA-обвязка, которая линкуется с решением: загрузка данных, вызов `solve`, замер времени, статус-протокол. |
| `pytools/` | `judge_problem.prepare`: генерация тестов и эталонных ответов (Python + PyTorch). |
| `sandbox/` | seccomp-политика для nsjail. |
| `deploy/` | Dockerfile gateway и executor (контекст сборки — корень репозитория). Сервисы описаны в корневом `compose.yaml` под профилями `judge` и `gpu`. |
| `problems/examples/` | Примеры задач (`vector_add`: данные на GPU, `matmul_host`: данные на CPU) и решения на все вердикты. |
| `tools/judge_cli.py` | Отправить посылку и смотреть события из консоли. |

## Как проходит посылка

1. **Gateway** проверяет структуру задачи и посылки (`SpecValidator`), публикует `JudgeJob` в `judge.jobs.<profile>` и ждёт подтверждения брокера. 202 означает, что задание надёжно лежит в очереди. Сразу публикуется событие `QUEUED`.
2. **Балансировка — pull-модель.** Каждый executor держит `GPU + prefetchExtra` консьюмеров с prefetch=1. Задание забирает тот, у кого есть свободная мощность. Executor упал — неподтверждённые задания вернутся в очередь. Новая машина начинает брать задания сразу после запуска. Задание, которое упало 5 раз (`x-delivery-limit`), уходит в `judge.jobs.dead`, и gateway публикует `INTERNAL_ERROR`, чтобы бэкенд не ждал вечно.
3. **Компиляция** (`COMPILING`), параллельно на CPU, пока GPU занята другим:
   - Проверка исходника: `#include` только из белого списка, нет `asm`, нет локальных и вычисляемых include.
   - `nvcc` в песочнице, ровно под архитектуру профиля (`-gencode arch=compute_86,code=sm_86`, без PTX и JIT). Объектник кэшируется по хэшу исходника и тулчейна: повторные посылки и перетестирование компилируются мгновенно.
   - По dependency-файлу компилятора (`-MD`) проверяется, что не подключено ничего из запрещённых библиотек, даже через макросы.
   - `cuobjdump -sass`: проверка на запрещённые SASS-инструкции профиля или задачи (например, `HMMA`, тензорные ядра).
   - `nm`: запрещены driver API (`cu*`), `dlopen/dlsym`, `cudaDeviceReset`, `fork/exec/system`, подмена символов обвязки.
   - Линковка с обвязкой (`harness.o` кэшируется на сигнатуру). Подключается только `cudart_static`, других CUDA-библиотек нет физически. Неверная сигнатура `solve` — ошибка линковки, `COMPILATION_ERROR` с понятным сообщением.
4. **Тесты** (`PREPARING`, только при первой посылке на новую версию задачи): `python -m judge_problem.prepare` на GPU, в песочнице, генерирует входы и эталонные ответы и кладёт их на диск. Ключ кэша — хэш сигнатуры, тестов и генератора. Кэш ограничен по объёму, вытесняется LRU.
5. **Запуск** (`TESTING k/n`): решение получает GPU эксклюзивно, одна посылка на GPU, чтобы замеры не мешали друг другу. Все тесты идут в одном процессе (контекст CUDA поднимается один раз). Вывод теста проверяется сразу после его завершения, пока GPU уже считает следующий. Остановка на первом упавшем тесте.
6. **Результат** (`FINISHED`): публикуется с подтверждением брокера. Только после этого делается ack задания. Если результат не удалось записать, задание будет перепроверено, вердикт не теряется.

## Шаблон задачи

Шаблон задаётся сигнатурой `solve` в задаче. Для каждого массива указано, где он лежит:

- `DEVICE` — указатель на память GPU (как на leetgpu: «реализуй ядро»);
- `HOST` — указатель на память CPU. Решение само решает, что считать на CPU, а что на GPU, и само копирует данные.

Считывать ввод не нужно: обвязка сама загружает данные и вызывает `void solve(...)` с C++-линковкой. Starter code хранится вместе с задачей, судья его не генерирует. Формат задачи: [docs/problem-format.md](docs/problem-format.md).

## Время

| Режим | Когда (по умолчанию) | Что меряется |
|---|---|---|
| `WALL` | все массивы на GPU | время от входа в `solve` до завершения всей GPU-работы (`cudaDeviceSynchronize`) |
| `GPU_KERNELS` | есть данные на CPU | суммарное время, когда GPU выполняла ядра решения (объединение интервалов). Копирования и CPU-часть не считаются. |

Режим можно переопределить в задаче (`timing.mode`). В любом режиме действует страховочный лимит на wall-время вызова (`limits.wallTimeLimitMs`, по умолчанию `max(3·TL, TL+2s)`).

Как устроен замер:
- **Прогрев.** Перед замерами `warmupRuns` вызовов на первом тесте (ленивая загрузка модулей, кэши, разгон частот).
- **Чистое состояние перед каждым вызовом.** Буферы выделяются заново, входы перечитываются, выходы заполняются `0xFF` (NaN) — защита от «уже посчитано». L2 сбрасывается.
- **Повторы только в серой зоне.** Если первый замер явно меньше TL или явно больше 1.25·TL, вердикт сразу. Между ними делается до `maxRuns` прогонов, сравнивается медиана. Так быстро и стабильно.
- **Фиксация частот** (`lock-graphics-clocks-mhz`, `nvidia-smi -lgc`) и persistence mode на нативном Linux. Во время прогона пишется телеметрия: частоты SM, температура, троттлинг. Она попадает в `JudgeResult.gpu`, чтобы можно было отбраковать шумные замеры.
- **Режим `GPU_KERNELS`.** Точки запуска ядер в CUDA runtime (`cudaLaunchKernel*`) оборачиваются при линковке (`-Xlinker --wrap`) и обрамляются событиями. Обходные пути запуска (driver API, CUDA Graphs) в этом режиме запрещены на уровне символов.

## Песочница и лимиты

nsjail (Linux и WSL2):
- **Изоляция:** новые user/pid/mount/net/ipc/uts namespaces, сети нет, корень только для чтения, отдельный uid.
- **Монтирование:** видны только системные каталоги, рабочая папка запуска и входы тестов (только чтение). Эталонные ответы в песочницу не попадают, сравнение идёт снаружи.
- **Устройства:** только устройства своей GPU (`/dev/nvidia*` или `/dev/dxg` на WSL2).
- **seccomp** (`sandbox/seccomp.kafel`): запрещены ptrace, mount, bpf, perf_event_open, модули ядра и т. п.
- **Лимиты:** RLIMIT на размер файлов и число дескрипторов, по желанию cgroups v2 (`use-cgroups-v2`).

Лимиты, которые следит сам executor (работает и без cgroups, и на WSL2):

| Что | Как |
|---|---|
| Зависшее ядро / бесконечный цикл | Watchdog по протоколу обвязки: на каждый вызов `solve` дедлайн `wallTimeLimit + 1s`. Дальше SIGKILL всего дерева процессов (драйвер уничтожает контекст и ядро), `TIME_LIMIT_EXCEEDED`, затем health-probe GPU. Если GPU не восстановилась, слот выключается, задания уходят другим executors, GPU перепроверяется каждые 30 с. |
| RAM | Опрос RSS дерева процессов каждые 50 мс: `memoryLimitMb` + объём данных теста + накладные расходы CUDA. Превышение — `MEMORY_LIMIT_EXCEEDED`. |
| Память GPU | `nvidia-smi -lms`, лимит `gpuMemoryLimitMb` поверх буферов теста. Превышение — `MEMORY_LIMIT_EXCEEDED`. |
| Компиляция | 30 с, 4 ГБ, тоже в песочнице. |

## Вердикты

`ACCEPTED`, `WRONG_ANSWER`, `TIME_LIMIT_EXCEEDED`, `MEMORY_LIMIT_EXCEEDED`, `RUNTIME_ERROR` (сигнал, ошибка CUDA вроде illegal address, ранний выход), `COMPILATION_ERROR`, `POLICY_VIOLATION` (запрещённый заголовок, asm, инструкция, символ), `INTERNAL_ERROR` (сбой судьи, можно перетестировать), `CANCELLED`.

Что видно участнику. Для открытых тестов (`sample: true`) в `TestResult.message` есть первое расхождение со значениями и stderr решения. Для закрытых — только «C: 57 of 1048576 elements differ». Подробности для жюри лежат в `TestResult.debug`, контестантам их показывать нельзя.

## Что разрешено (политика)

По умолчанию запрещено всё, кроме стандартной библиотеки C/C++ и CUDA runtime:
- `cuda_runtime.h`, `cuda_fp16.h` и `cuda_bf16.h`;
- `cooperative_groups`, `mma.h`;
- libcu++ (`cuda/...`).

Thrust, CUB, cuBLAS, cuDNN, CUTLASS, cuRAND, driver API (`cuda.h`) и inline PTX запрещены. Настройка на двух уровнях:
- **профиль GPU** (`judge.executor.profiles.<name>.policy`): `forbidden-sass`, `extra-allowed-headers`, `allow-inline-asm`;
- **задача** (`policy`): `allowHeaders` (например, `thrust/`), `forbidHeaders`, `forbidSass`, `allowSass`, `allowInlineAsm`.

Флаги nvcc и архитектура тоже приходят с задачей (`compile.flags`, `compile.arch`); в конфигурации executor остаются только значения по умолчанию. Флаги проверяются по белому списку, см. [docs/problem-format.md](docs/problem-format.md).

## Зафиксированный тулчейн

Одна версия на все задачи. Зафиксирована в `deploy/executor.Dockerfile`:

| Компонент | Версия |
|---|---|
| CUDA / nvcc | 12.8.1 (`nvidia/cuda:12.8.1-devel-ubuntu24.04`) |
| Host-компилятор | g++ 13 (`-ccbin g++-13`) |
| Флаги | `-std=c++17 -O3 --expt-relaxed-constexpr -gencode arch=compute_86,code=sm_86` |
| Python / PyTorch / NumPy | 3.12 / 2.9.1+cu128 / 2.2.6 |
| nsjail | 3.6 |
| RabbitMQ | 4.1.8 |
| JVM сервисов | JDK 26 (Temurin), как у всего проекта |

Executor при старте сверяет версии CUDA, g++ и torch (`judge.executor.toolchain.expected-*`). При расхождении он не запустится. Строка с версиями пишется в каждый `JudgeResult.toolchain`, хэш тулчейна входит в ключ кэша компиляции.

## Логирование

Каждая строка лога executor содержит `submissionId`, номер попытки и GPU (MDC: `submissionId`, `attempt`, `problem`, `executor`, `gpu`):

```
INFO [sub=s-42 a=1 gpu=0] JudgePipeline : Judging submission: problem=vector-add v1 tests=5 attempt=1 waited=12ms
INFO [sub=s-42 a=1 gpu=]  Builder       : nvcc finished in 1840ms: ok
INFO [sub=s-42 a=1 gpu=0] HarnessRunner : Started solution on gpu0(NVIDIA GeForce RTX 3060) (5 tests, timing=WALL, TL=20ms, ...)
INFO [sub=s-42 a=1 gpu=]  JudgePipeline : Verdict ACCEPTED in 2410ms (max time 1.732 ms)
```

- **Сбои судьи** (`INTERNAL_ERROR`, упал nvcc, nsjail, генератор, GPU не прошла probe): ERROR со stack trace, командой, кодом выхода и хвостом stderr. Рабочая папка запуска сохраняется в `paths.failed` (последние 50).
- **Здоровье GPU:** переходы healthy/unhealthy пишутся в лог executor и в heartbeat. Gateway логирует, когда executor пропал или у него сломалась GPU.
- **JSON-логи для сборщика:** `SPRING_PROFILES_ACTIVE=json` (logstash-формат).
- **Метрики:** `/actuator/prometheus`: `judge.executor.stage` (compile/prepare/run), `judge.executor.verdicts`, `judge.executor.requeues`, `judge.gateway.submissions`, `judge.gateway.healthy_gpus`.

## Контракт с бэкендом

- `POST /api/v1/submissions` с телом `SubmissionRequest`: `{submissionId, problem: ProblemSpec, source, gpuProfile?}`. Ответы: 202 — принято; 200 + `duplicate: true` — повтор id; 400 + `details` — невалидно; 503 — брокер недоступен.
- `DELETE /api/v1/submissions/{id}` — отмена (best effort).
- `POST /api/v1/problems/validate` — проверить задачу при сохранении.
- `GET /api/v1/executors`, `GET /api/v1/queues` — мониторинг.
- Результаты: бэкенд слушает очередь `judge.events` (`JudgeEvent`, JSON).
  - События одной попытки идут по порядку (`seq`).
  - Новая попытка (`attempt`) начинается заново с `COMPILING`.
  - `FINISHED` — терминальное событие. Возможны дубликаты при сбоях, поэтому обработку нужно делать идемпотентной по `(submissionId, attempt, seq)`.
- Авторизации между бэкендом и gateway пока нет (обсудим отдельно).

## Сборка и тесты

Модули `:judge-api`, `:judge-gateway`, `:judge-executor` входят в общую Gradle-сборку (корневой `settings.gradle.kts`), JDK 26:

```bash
./gradlew :judge-api:test :judge-gateway:test :judge-executor:test
```

Тестам executor нужен `python3`: поддельное решение в `HarnessRunnerTest` написано на Python. GPU и Docker тестам не нужны.

## Запуск

Сервисы judge описаны в корневом `compose.yaml` под профилями. Обычный `docker compose up -d --wait` по-прежнему поднимает только PostgreSQL.

Локально без GPU (RabbitMQ + gateway на <http://localhost:8090>, Swagger: `/swagger-ui.html`):

```bash
docker compose --profile judge up -d --wait
python3 judge/tools/judge_cli.py validate judge/problems/examples/vector_add
```

GPU-машина, нативный Linux:
1. Драйвер NVIDIA ≥ 570 (нужен для CUDA 12.8), Docker и `nvidia-container-toolkit`.
2. Из корня репозитория:

```bash
docker compose --profile gpu up -d --wait --build
```

3. Фиксация частот (опционально, нужен root на хосте), для RTX 3060 базовая частота 1320 МГц:

```bash
JUDGE_LOCK_CLOCKS=1320,1320 docker compose --profile gpu up -d --wait
```

4. Проверка (для `--watch` нужен `pip install pika`):

```bash
python3 judge/tools/judge_cli.py submit judge/problems/examples/vector_add judge/problems/examples/vector_add/solutions/accepted.cu --watch
```

GPU-машина, Windows + WSL2:
- **Docker:** нужен Docker Desktop с WSL2-backend (или Docker Engine внутри WSL2 + `nvidia-container-toolkit`). GPU прокидывается через `/dev/dxg`, executor определяет это сам.
- **Частоты** из WSL2 не фиксируются: при необходимости `nvidia-smi.exe -lgc 1320,1320` в PowerShell от администратора.
- **TDR Windows** убивает ядра дольше ~2 с на GPU с дисплеем. Для длинных TL нужно увеличить `TdrDelay` в реестре.

Без Docker (systemd на хосте) — те же переменные, что в `compose.yaml`, плюс `JUDGE_HARNESS_DIR`, `JUDGE_PYTOOLS_DIR`, `JUDGE_SECCOMP_POLICY`. nsjail требует root (для user namespace с другим uid).

## Известные ограничения и что обсудить

- **Время меряется внутри процесса решения.** Злонамеренное решение теоретически может испортить данные обвязки или статус-канал. Корректность ответа проверяется снаружи песочницы и не обходится. Время ограничено внешним watchdog (не больше `wallTimeLimit` на вызов). Полностью внешний замер — следующий шаг, если будет рейтинг по времени.
- **`GPU_KERNELS` не считает CPU-часть.** Решение, которое всё считает на CPU (`matmul_host/solutions/cpu_only.cu`), ограничено только wall-лимитом. Нужно решить, что считать временем для задач с данными на CPU (см. вопрос в PR).
- **Запрет inline asm — по исходнику.** Его можно обойти token pasting в макросах. Ограничения на инструкции надёжно задаются через `forbidden-sass`.
- **Дедупликация в gateway — в памяти одного инстанса.** Если gateway будет несколько, нужен общий storage (Redis/БД бэкенда).
- **Проверка корректности самой задачи** (что тесты ловят неверные решения, допуски не пропускают fp16) — обсудим отдельно.
