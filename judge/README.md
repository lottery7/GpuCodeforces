# Тестирующая система (judge)

Часть 3 проекта GPU Codeforces. Принимает посылки на одном из процессоров (реальная GPU или процессор в эмуляторе), компилирует их, запускает в песочнице, проверяет ответ с допуском для float и асинхронно отдаёт вердикт со score бэкенду.

```
Backend ──POST /api/v1/submissions──▶ judge-gateway ──▶ RabbitMQ: judge.jobs.<processor> (quorum)
        ◀──────── 202 Accepted                                 │  competing consumers (pull)
                                                               ▼
                                              judge-executor × N (одна машина = один executor,
                                              очередь на каждый свой процессор, по слоту на GPU
                                              или работника эмулятора + предвыборка для компиляции)
                                                               │
Backend ◀── RabbitMQ: judge.events (QUEUED → COMPILING → PREPARING → TESTING k/n → FINISHED) ──┘
```

| Модуль | Что делает |
|---|---|
| `judge-api` | Контракты (Kotlin data classes): `SubmissionRequest`, `ProblemSpec`, `JudgeEvent`, вердикты, каталог процессоров (`Processors`), имена очередей. Бэкенд может подключить модуль как зависимость. |
| `judge-gateway` | Spring Boot. Валидирует посылку, кладёт задание в очередь её процессора (с publisher confirm), сразу отвечает 202. Дедупликация по `submissionId`, отмена, мониторинг executors, очередей и процессоров. Контракт HTTP — [docs/openapi.yaml](docs/openapi.yaml), контроллеры реализуют сгенерированные по нему интерфейсы; Swagger UI: `/swagger-ui.html`. |
| `judge-executor` | Spring Boot. Забирает задания из очередей своих процессоров, передаёт реализации процессора (`ProcessorBackend`): та компилирует, готовит тесты и запускает решение в nsjail; публикует события. |
| `harness/` | C++/CUDA-обвязка, которая линкуется с решением: загрузка данных, вызов `solve`, замер времени, статус-протокол. |
| `pytools/` | `judge_problem.prepare`: генерация тестов и эталонных ответов (Python + PyTorch). |
| `sandbox/` | seccomp-политика для nsjail. |
| `deploy/` | Dockerfile gateway и executor (контекст сборки — корень репозитория). Сервисы описаны в корневом `compose.yaml` под профилями `judge` и `gpu`. |
| `problems/examples/` | Примеры задач (`vector_add`: данные на GPU, `matmul_host`: данные на CPU) и решения на все вердикты. |
| `docs/problem-format.md` | Формат задачи: сигнатура, процессоры, тесты генератора. |
| `tools/judge_cli.py` | Отправить посылку и смотреть события из консоли. |

## Процессоры

Посылка приходит с **процессором** и **языком**; задача объявляет, на каких процессорах и языках её можно сдавать (`ProblemSpec.processors`, см. [docs/problem-format.md](docs/problem-format.md)). Результат — **score с типом**: меньше — лучше, сравнимы только score одного процессора.

| id | Что это | Языки | Тип score | Реализация |
|---|---|---|---|---|
| `gpu-cuda` | реальная GPU NVIDIA | `cuda` | `TIME_MS` | есть |
| `rtl` | схема участника, Verilator + Yosys | `systemverilog` | `CYCLES_X_DEPTH_X_TRANSISTORS` | есть |
| `riscv-rvv` | векторный RISC-V, QEMU user-mode | `c`, `cpp`, `asm` | `WEIGHTED_INSTRUCTIONS` | есть |
| `cuda-sim` | GPU NVIDIA в GPGPU-Sim (QV100) | `cuda` | `CYCLES` | есть |
| `coral-npu` | Coral NPU (MPACT, Verilator) | `c`, `cpp` | `CYCLES` | отложен: тулчейн и симулятор собираются только Bazel-сборкой проекта, а симулятор не умеет принимать входы и отдавать выходы — их пришлось бы вшивать в программу и печатать из неё же, то есть подделываемо |

Каталог статический и общий для gateway, executor и бэкенда (`judge-api`, `Processors`); `GET /api/v1/processors` показывает его вместе с тем, принимает ли gateway посылки на процессор и есть ли живой executor. Gateway принимает посылки только на процессоры из `JUDGE_PROCESSORS`, executor обслуживает процессоры из своего `JUDGE_PROCESSORS` и не запускается, если реализации процессора у него нет.

Устройство executor: `JudgePipeline` проверяет задание, создаёт рабочую папку, отвечает за повторы, `INTERNAL_ERROR`, отмену до старта и итоговое событие, а саму проверку отдаёт реализации процессора (`processor/ProcessorBackend`). `gpu-cuda` — нынешний путь на реальной GPU (`processor/gpucuda/`). Эмулируемые процессоры используют общий цикл `processor/EmulatorRun`: один процесс симулятора на тест, сторож по времени хоста, проверка ответа, сравнение score с лимитом; слоты для них — пул работников (`judge.executor.emulators.<id>.workers`, по умолчанию 1: симулятор однопоточный, один работник — одно ядро).

### `rtl`

Посылка — схема на SystemVerilog (интерфейс модуля, такты и метрика — в [docs/problem-format.md](docs/problem-format.md#процессор-rtl)). Реализация — `processor/rtl/`:

1. Проверка исходника: `` `include `` и `$readmem*` — `POLICY_VIOLATION`.
2. Yosys в песочнице: `synth -top solve -flatten` и `dffunmap`, площадь в транзисторах (`stat -json -tech cmos`, вес у каждого типа ячейки), глубина логики (`ltp -noff`, ячеек на самом длинном пути между регистрами), нетлист. Score теста — такты × глубина × транзисторы. Защёлки и асинхронные сбросы (их Yosys не взвешивает) и комбинационные петли (у них нет глубины) — `COMPILATION_ERROR`. Имена проводов и ячеек участника заменяются сгенерированными (`rename -hide`), верхний модуль переименовывается в `judge_top`: Verilator не принимает модуль `solve` (это ключевое слово SystemVerilog), а Yosys принимает. Порты нетлиста сверяются с интерфейсом задачи.
3. Verilator в песочнице собирает симулятор из нетлиста и тестбенча судьи (`RtlTestbench`, доверенный код, как обвязка `gpu-cuda`). Симулируется нетлист: в нём нет ничего, кроме схемы, и это ровно та схема, чью площадь посчитали.
4. Тесты — через `EmulatorRun`: один процесс симулятора на тест, входы — те же `.bin`, что у `gpu-cuda`, такты тестбенч сообщает строкой с nonce.

Результат сборки (и ошибка сборки) кэшируется по исходнику, интерфейсу, тестбенчу и версиям тулчейна. Executor `rtl` — образ `deploy/executor-emu.Dockerfile` (без GPU, PyTorch для CPU, OSS CAD Suite); GPU ему не нужна.

### `riscv-rvv`

Посылка — `solve` на C, C++ или ассемблере (интерфейс и метрика — в [docs/problem-format.md](docs/problem-format.md#процессор-riscv-rvv)). Реализация — `processor/riscv/`:

1. Кросс-компилятор в песочнице собирает посылку вместе с обвязкой судьи (`RiscvHarness`) в статический ELF; результат кэшируется.
2. Тест — один запуск `qemu-riscv64` с плагином судьи `riscv/judge_plugin.c` (собирается в образе): он считает инструкции, моделирует L1I/L1D/L2 и в конце пишет итог с nonce в stdout.
3. Из итога вычитаются числа обвязки с пустой `solve` на том же тесте (они считаются один раз и кэшируются).

**Ограничение, принятое так же, как у `cuda-sim`.** Решение исполняется в одном процессе с QEMU и его плагином. Канал отчёта защищён: плагин через фильтр системных вызовов QEMU отклоняет любые операции решения с stdout и всё, чем до него можно добраться снова (`/proc`, `dup`, `fork`, `exec`, файлы вне каталогов теста); попытки считаются (`denied_syscalls`). Но счётчики лежат в памяти процесса QEMU, а user mode не проверяет адреса загрузок и записей гостя: решение, которое найдёт в памяти страницу данных плагина, может переписать свой score. Это целенаправленная атака, но не теоретическая: на прототипе память хоста по настоящим адресам читалась и с `-R`, а сканирование 4 ГиБ занимало секунды; граница безопасности для машины — nsjail, под вопросом только честность score. Подозрительный score стоит перепроверить по исходнику.

### `cuda-sim`

Посылка — CUDA, как на `gpu-cuda`, исполняется в GPGPU-Sim на модели QV100 (интерфейс и метрика — в [docs/problem-format.md](docs/problem-format.md#процессор-cuda-sim)). Реализация — `processor/cudasim/`: nvcc в песочнице собирает решение с PTX `compute_70` и своей обвязкой (`CudaSimHarness`: данные в памяти GPU, один вызов `solve`) и линкует с `libcudart` симулятора — неподдержанная функция runtime становится ошибкой компиляции; `cudaMemcpy*` в решении и `shfl.sync` в его PTX отклоняются. На тест — один процесс с `gpgpusim.config` модели в рабочем каталоге; такты — последняя `gpu_tot_sim_cycle` перед строкой обвязки `done`. На тест — переменные окружения, которые GPGPU-Sim ждёт от своего `setup_environment` (`CUOBJDUMP_SIM_FILE` и другие: без них симулятор не находит PTX в бинарнике). Образ — `deploy/executor-cuda-sim.Dockerfile` (CUDA 12.8 devel, GPGPU-Sim собирается в нём; linux/amd64 и linux/arm64 — образ amd64 под эмуляцией на arm64 не запустится: nsjail не может поставить фильтр seccomp для x86), сервис `judge-executor-cuda-sim` под профилем `cuda-sim`. У `libcudart` симулятора в образе прописан soname `libcudart.so.12`: версию CUDA программы GPGPU-Sim узнаёт по этому имени в `ldd`.

**Ограничения, принятые пользователем.** Симулятор живёт в одном процессе с решением и печатает статистику в общий stdout: решение может напечатать поддельные строки, отдельного канала у GPGPU-Sim нет. Работа на CPU тактов не стоит, поэтому все массивы — `DEVICE`, а `cudaMemcpy*` в решении запрещены проверкой символов: это рекомендательная проверка, граница безопасности — nsjail. На прототипе такты совпадали на повторных прогонах (и между amd64 и arm64); `__shfl_*_sync` симулятор считает молча неверно, поэтому `shfl.sync` в PTX посылки запрещён, а CUDA Graphs и текстурных объектов в его `libcudart` нет — такая посылка не линкуется.

Детерминизм: гость видит пути своих файлов, аргументы и окружение, и их длина сдвигает стек. Под nsjail всё это одинаково при каждом запуске (`/work`, `/data`, `/sim`, фиксированное окружение), поэтому повторная посылка даёт тот же score. QEMU запускается из `/bin/sh`: nsjail делает команду PID 1 пространства имён, а PID 1 игнорирует сигнал, которым QEMU сообщает о падении гостя, и QEMU зависал бы.

**Score посылки** — максимум по замерным тестам (`TestResult.measured`), задан только при `ACCEPTED`. Тест генератора может быть незамерным (`"measure": False`): с него берётся только проверка ответа, его score не идёт в результат и не сравнивается с лимитом (на `gpu-cuda` для него действует только страховочный wall-лимит). Превышение лимита score — `TIME_LIMIT_EXCEEDED`, в сообщении названа единица («Cycle limit exceeded»).

Результаты сравнимы при одинаковых `judgeHash` задачи и процессоре. Версии эмуляторов зафиксированы, в сравнимость они не входят.

## Как проходит посылка

1. **Gateway** проверяет структуру задачи и посылки (`SpecValidator`): процессор принимается этим gateway и объявлен задачей, язык разрешён, у процессора есть лимит. Затем публикует `JudgeJob` в `judge.jobs.<processor>` и ждёт подтверждения брокера. 202 означает, что задание надёжно лежит в очереди. Сразу публикуется событие `QUEUED`.
2. **Балансировка — pull-модель.** Каждый executor держит на каждый свой процессор `слоты + prefetchExtra` консьюмеров с prefetch=1 (слоты — GPU у `gpu-cuda`, работники у эмуляторов). Задание забирает тот, у кого есть свободная мощность. Executor упал — неподтверждённые задания вернутся в очередь. Новая машина начинает брать задания сразу после запуска. Задание, которое упало 5 раз (`x-delivery-limit`), уходит в `judge.jobs.dead`, и gateway публикует `INTERNAL_ERROR`, чтобы бэкенд не ждал вечно.
3. **Компиляция** (`COMPILING`), параллельно на CPU, пока GPU занята другим:
   - Проверка исходника: `#include` только из белого списка, нет `asm`, нет локальных и вычисляемых include.
   - `nvcc` в песочнице, ровно под архитектуру профиля (`-gencode arch=compute_86,code=sm_86`, без PTX и JIT). Объектник кэшируется по хэшу исходника и тулчейна: повторные посылки и перетестирование компилируются мгновенно.
   - По dependency-файлу компилятора (`-MD`) проверяется, что не подключено ничего из запрещённых библиотек, даже через макросы.
   - `cuobjdump -sass`: проверка на запрещённые SASS-инструкции профиля или задачи (например, `HMMA`, тензорные ядра).
   - `nm`: запрещены driver API (`cu*`), `dlopen/dlsym`, `cudaDeviceReset`, `fork/exec/system`, подмена символов обвязки.
   - Линковка с обвязкой (`harness.o` кэшируется на сигнатуру). Подключается только `cudart_static`, других CUDA-библиотек при линковке нет (в песочнице запуска они есть, см. ограничения). Неверная сигнатура `solve` — ошибка линковки, `COMPILATION_ERROR` с понятным сообщением.
4. **Тесты** (`PREPARING`, только при первой посылке на новую версию задачи и процессор): `python -m judge_problem.prepare --processor <id>` в песочнице берёт список тестов из `tests()` генератора и генерирует входы и эталонные ответы только тех тестов, что идут на этом процессоре. На executor с GPU генерация занимает GPU-слот (и считает на GPU, если torch её видит), на executor без GPU — считает на CPU. Executor проверяет объявленные тесты (скаляры, размеры, лимиты, процессоры, хотя бы один замерный) так же строго, как остальной вывод генератора. Ключ кэша — хэш сигнатуры, генератора, процессоров задачи, процессора и реально использованного устройства. Кэш ограничен по объёму, вытесняется LRU.
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
| `WALL` | всегда, если задача не просит иное | время от входа в `solve` до завершения всей GPU-работы (`cudaDeviceSynchronize`) |
| `GPU_KERNELS` | только по явному `timing.mode` | суммарное время, когда GPU выполняла ядра решения (объединение интервалов). Копирования и CPU-часть не считаются, поэтому решение, считающее на CPU, получает время около нуля. |

Режим можно переопределить в задаче (`timing.mode`). В любом режиме действует страховочный лимит на wall-время вызова (`limits.wallTimeLimitMs`, по умолчанию `max(3·TL, TL+2s)`).

Как устроен замер:
- **Прогрев.** Перед замерами `warmupRuns` вызовов на первом тесте (ленивая загрузка модулей, кэши, разгон частот).
- **Чистое состояние перед каждым вызовом.** Буферы выделяются заново, входы перечитываются, выходы заполняются `0xFF` (NaN) — защита от «уже посчитано». L2 сбрасывается.
- **Повторы только в серой зоне.** Если первый замер явно меньше TL или явно больше 1.25·TL, вердикт сразу. Между ними делается до `maxRuns` прогонов, сравнивается медиана. Цена: повторы идут в том же процессе на тех же входах, и решение, запомнившее ответ, на повторах отвечает мгновенно — фактический лимит для такого решения равен 1.25·TL (см. ограничения).
- **Фиксация частот** (`nvidia-smi -lgc`) делается на хосте: контейнер executor больше не привилегированный и менять частоты не может. Во время прогона пишется телеметрия: частоты SM, температура, троттлинг. Она попадает в `JudgeResult.gpu`, чтобы можно было отбраковать шумные замеры.
- **Режим `GPU_KERNELS`.** Точки запуска ядер в CUDA runtime (`cudaLaunchKernel*`) оборачиваются при линковке (`-Xlinker --wrap`) и обрамляются событиями. Обходные пути запуска (driver API, CUDA Graphs) в этом режиме запрещены на уровне символов.

## Песочница и лимиты

nsjail (Linux и WSL2):
- **Изоляция:** новые user/pid/mount/net/ipc/uts namespaces, сети нет, корень только для чтения. У каждой одновременной песочницы свой uid из пула: лимит на процессы считается по uid, и с общим uid одна посылка могла бы уронить компиляции и запуски соседних.
- **Монтирование:** видны только системные каталоги, рабочая папка запуска и входы тестов (только чтение). Эталонные ответы в песочницу не попадают, сравнение идёт снаружи.
- **Устройства:** только устройства своей GPU (`/dev/nvidia*` или `/dev/dxg` на WSL2).
- **seccomp** (`sandbox/seccomp.kafel`): запрещены ptrace, mount (и новый mount API), bpf, perf_event_open, io_uring, userfaultfd, модули ядра, создание вложенных namespaces. Если файл политики не найден, executor не стартует.
- **Лимиты:** RLIMIT на размер файлов, число дескрипторов, число процессов и стек; по желанию cgroups v2 (`use-cgroups-v2`).
- **Запись:** решению доступна на запись только папка выходных файлов; бинарник и манифест — только чтение.
- **Файлы после недоверенного кода.** Executor работает от root, поэтому всё, что могло записать решение или генератор, читается и удаляется без перехода по символическим ссылкам. Выход решения, оказавшийся ссылкой (например, на эталон), — `WRONG_ANSWER`. Выход генератора принимается, только если в нём ровно ожидаемые файлы нужного размера.
- **Протокол статусов.** Обвязка живёт в одном процессе с решением, и решение может писать в статус-канал само. Поэтому тест засчитывается только после сравнения его выходного файла с эталоном, тесты идут строго по порядку, а строки, которых обвязка написать не могла, завершают запуск с `RUNTIME_ERROR`.

Лимиты, которые следит сам executor (работает и без cgroups, и на WSL2):

| Что | Как |
|---|---|
| Зависшее ядро / бесконечный цикл | Watchdog по протоколу обвязки: на каждый вызов `solve` дедлайн `wallTimeLimit + 1s`. Дальше SIGKILL всего дерева процессов (драйвер уничтожает контекст и ядро), `TIME_LIMIT_EXCEEDED`, затем health-probe GPU. Если GPU не восстановилась, слот выключается, задания уходят другим executors, GPU перепроверяется каждые 30 с. |
| RAM | Опрос RSS дерева процессов каждые 50 мс: `memoryLimitMb` + объём данных теста + накладные расходы CUDA. Превышение — `MEMORY_LIMIT_EXCEEDED`. |
| Память GPU | `nvidia-smi -lms`, лимит `gpuMemoryLimitMb` поверх буферов теста. Превышение — `MEMORY_LIMIT_EXCEEDED`. |
| Диск | Объём папки выходных файлов (она в RAM) опрашивается вместе с RSS; превышение — `RUNTIME_ERROR`. |
| Компиляция | 30 с, 4 ГБ (RLIMIT_AS), объектник до 64 МБ, тоже в песочнице. |

## Вердикты

`ACCEPTED`, `WRONG_ANSWER`, `TIME_LIMIT_EXCEEDED`, `MEMORY_LIMIT_EXCEEDED`, `RUNTIME_ERROR` (сигнал, ошибка CUDA вроде illegal address, ранний выход), `COMPILATION_ERROR`, `POLICY_VIOLATION` (запрещённый заголовок, asm, инструкция, символ), `INTERNAL_ERROR` (сбой судьи, можно перетестировать), `CANCELLED`.

Что видно участнику. Для открытых тестов (`sample: true`) в `TestResult.message` есть подробности: первое расхождение со значениями, текст ошибки CUDA. Для закрытых — только фиксированный текст («Wrong answer», «CUDA error», «Allocation failed»): и число несовпавших элементов, и текст ошибки решение может использовать, чтобы посылка за посылкой вытащить закрытый вход. stderr решения попадает в результат, только если все тесты задачи открытые. Всё, что скрыто, пишется в лог executor с `submissionId` и в поток событий не попадает; `TestResult.debug` заполняется только для открытых тестов.

## Что разрешено (политика)

По умолчанию запрещено всё, кроме стандартной библиотеки C/C++ и CUDA runtime:
- `cuda_runtime.h`, `cuda_fp16.h` и `cuda_bf16.h`;
- `cooperative_groups`, `mma.h`;
- libcu++ (`cuda/...`).

Thrust, CUB, cuBLAS, cuDNN, CUTLASS, cuRAND, driver API (`cuda.h`) и inline PTX запрещены. Настройка на двух уровнях:
- **executor `gpu-cuda`** (`judge.executor.profile.policy`): `forbidden-sass`, `extra-allowed-headers`, `allow-inline-asm`;
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

Эмулируемые процессоры — в `deploy/executor-emu.Dockerfile`:

| Компонент | Версия |
|---|---|
| OSS CAD Suite | сборка 2026-11-01 (архивы linux-x64 и linux-arm64 по sha256) |
| QEMU (`riscv-rvv`) | 11.1.3 из исходников с `--enable-plugins` (по sha256), плагин судьи — `riscv/judge_plugin.c` |
| Кросс-компилятор RISC-V | GCC 13.3 (`gcc-riscv64-linux-gnu` Ubuntu 24.04), RV64GCV, VLEN 256 |
| GPGPU-Sim (`cuda-sim`, `deploy/executor-cuda-sim.Dockerfile`) | accel-sim/gpgpu-sim_distribution, ветка `dev`, коммит `4195dd4f`; модель `SM7_QV100`; CUDA 12.8.1 |
| Yosys / Verilator | 0.70+286 / 5.053 |
| Компилятор симулятора | g++ 13 (`-std=c++17`) |
| Python / PyTorch / NumPy | 3.12 / 2.9.1+cpu / 2.2.6 |

Executor при старте сверяет версии CUDA, g++ и torch (`judge.executor.toolchain.expected-*`), на `rtl` — Yosys и Verilator (`judge.executor.rtl.expected-*`), на `riscv-rvv` — QEMU и GCC (`judge.executor.riscv.expected-*`), на `cuda-sim` — коммит GPGPU-Sim. При расхождении он не запустится. Строка с версиями пишется в каждый результат (`JudgeResult.processor.version`), хэш тулчейна входит в ключ кэша компиляции.

Heartbeat передает ту же строку у каждого процессора (`ExecutorHeartbeat.processors[].version`). Backend включает
версию `gpu-cuda` в ключ окружения эталонных замеров и принимает результат только при точном совпадении
(`JudgeResult.processor.version`). Executor'ы `gpu-cuda` со смешанными версиями не дают готовых эталонных времен.
Mock передает `mock (no compiler, no GPU, no emulator)` в heartbeat и результате.

## Логирование

Каждая строка лога executor содержит `submissionId`, номер попытки, процессор и GPU (MDC: `submissionId`, `attempt`, `problem`, `processor`, `executor`, `gpu`):

```
INFO [sub=s-42 a=1 proc=gpu-cuda gpu=0] JudgePipeline : Judging submission: problem=vector-add v1 processor=gpu-cuda language=cuda attempt=1 waited=12ms
INFO [sub=s-42 a=1 proc=gpu-cuda gpu=]  Builder       : nvcc finished in 1840ms: ok
INFO [sub=s-42 a=1 proc=gpu-cuda gpu=0] HarnessRunner : Started solution on gpu0(NVIDIA GeForce RTX 3060) (5 tests, timing=WALL, TL=20.0ms, ...)
INFO [sub=s-42 a=1 proc=gpu-cuda gpu=]  JudgePipeline : Verdict ACCEPTED in 2410ms (score 1.732 TIME_MS)
```

- **Сбои судьи** (`INTERNAL_ERROR`, упал nvcc, nsjail, генератор, GPU не прошла probe): ERROR со stack trace, командой, кодом выхода и хвостом stderr. Рабочая папка запуска сохраняется в `paths.failed` (последние 50).
- **Здоровье GPU:** переходы healthy/unhealthy пишутся в лог executor и в heartbeat. Gateway логирует, когда executor пропал или у него сломалась GPU.
- **JSON-логи для сборщика:** `SPRING_PROFILES_ACTIVE=json` (logstash-формат).
- **Метрики:** `/actuator/prometheus`: `judge.executor.stage` (compile/prepare/run, по процессорам), `judge.executor.verdicts` (по процессорам), `judge.executor.requeues`, `judge.gateway.submissions`, `judge.gateway.alive_executors{processor}`, `judge.gateway.healthy_gpus`.

## Контракт с бэкендом

HTTP API описан в [docs/openapi.yaml](docs/openapi.yaml); ниже — то, что важно знать сверх схем.

- `POST /api/v1/submissions` с телом `SubmissionRequest`: `{submissionId, problem: ProblemSpec, source, processor, language}`; процессор и язык обязательны. Ответы: 202 — принято, в ответе `jobId`; 200 + `duplicate: true` — повтор id, `jobId` первого запроса; 409 — этот же id прямо сейчас принимается другим запросом, повторите позже; 400 + `details` — невалидно; 413 — тело больше 8 МБ; 503 — брокер недоступен.
- `DELETE /api/v1/submissions/{id}` — отмена. Это только оптимизация: сообщение не сохраняется и теряется, если executor процессора не подключён, перезапустился или задание ждёт дольше часа; API при этом всё равно отвечает 202. Решает бэкенд: после отмены он игнорирует любой результат, а на отменённую посылку может прийти обычный вердикт.
- `POST /api/v1/problems/validate` — проверить задачу при сохранении.
- `GET /api/v1/executors`, `GET /api/v1/queues`, `GET /api/v1/processors` — мониторинг.
- В `JudgeResult` — `score` (`{value, type}`, только при `ACCEPTED`), `metrics` (числа теста, давшего score), `processor` (`id`, `version`, `model`, `executorId`); `timingMode` и `gpu` — только у `gpu-cuda`. В `TestResult` — `score`, `measured`, `metrics`.
- Результаты: бэкенд слушает очередь `judge.events` (`JudgeEvent`, JSON).
  - **Порядок доставки не гарантирован:** события идут по разным каналам брокера. Сортируйте по `(attempt, seq)` и не откатывайте состояние назад после `FINISHED`.
  - `QUEUED` (attempt 0, от gateway) необязателен: быстрый executor может прислать `COMPILING` или даже `FINISHED` раньше него.
  - Новая попытка (`attempt`) начинается заново с `COMPILING`.
  - `FINISHED` — терминальное событие. Возможны дубликаты при сбоях, поэтому обработку нужно делать идемпотентной по `(jobId, attempt, seq)`. `jobId` — уникальный id задания от gateway: после таймаута публикации или перетестирования у одного `submissionId` может оказаться два задания, и авторитетно то, чей `jobId` вернул ответ 202. Событие о задании, которое не удалось осудить после всех попыток, приходит от gateway с `attempt = 1000000`.
- Авторизации между бэкендом и gateway пока нет (обсудим отдельно).

## Сборка и тесты

Модули `:judge-api`, `:judge-gateway`, `:judge-executor` входят в общую Gradle-сборку (корневой `settings.gradle.kts`), JDK 26:

```bash
./gradlew :judge-api:test :judge-gateway:test :judge-executor:test
```

Тестам executor нужен `python3`: поддельное решение в `HarnessRunnerTest` и поддельный симулятор в `EmulatorRunTest` написаны на Python. GPU, Docker и эмуляторы тестам не нужны. Тесты генератора: `cd judge/pytools && python3 -m unittest discover -s tests`.

Тесты с настоящими эмуляторами помечены тегом JUnit `emulator` и идут отдельной задачей (обычный `test` их пропускает): `RtlEmulatorTest` и `RiscvEmulatorTest` судят все решения `problems/examples/vector_add_rtl` и `vector_add_riscv` настоящими тулчейнами, без песочницы. Нужны OSS CAD Suite (`RTL_HOME` — корень архива), QEMU с плагинами и собранный плагин судьи (`RISCV_QEMU`, `RISCV_PLUGIN`; только Linux), кросс-компиляторы `riscv64-linux-gnu-gcc/g++` и Python с torch (`JUDGE_PYTHON`); в CI это задание `emulators` в `.github/workflows/judge.yml`. Проще всего — внутри образа эмуляторов, где всё это уже есть (нужен только JDK 26):

```bash
RTL_HOME=/opt/oss-cad-suite RISCV_QEMU=/opt/qemu/bin/qemu-riscv64 RISCV_PLUGIN=/opt/judge/riscv/judge_plugin.so \
JUDGE_PYTHON=/opt/venv/bin/python ./gradlew :judge-executor:emulatorTest
```

`cuda-sim` проверяется иначе: решения примера `vector_add_cudasim` судятся через стенд с его образом (`--profile cuda-sim`, ниже). Задания в CI для него нет: образ CUDA и сборка GPGPU-Sim для него слишком тяжелы.

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

Эмулируемые процессоры (`rtl`, `riscv-rvv` — профиль `emu`; `cuda-sim` — профиль `cuda-sim`), без GPU. Процессоры должны быть и в `JUDGE_PROCESSORS` gateway:

```bash
JUDGE_PROCESSORS=gpu-cuda,rtl,riscv-rvv,cuda-sim docker compose --profile judge --profile emu --profile cuda-sim up -d --wait --build
python3 judge/tools/judge_cli.py submit judge/problems/examples/vector_add_rtl judge/problems/examples/vector_add_rtl/solutions/accepted.sv --processor rtl --watch
```

`JUDGE_RTL_WORKERS`, `JUDGE_RISCV_WORKERS`, `JUDGE_CUDA_SIM_WORKERS` (по умолчанию 2) — сколько симуляций каждого процессора идёт одновременно, по ядру на каждую.

GPU-машина, Windows + WSL2:
- **Docker:** нужен Docker Desktop с WSL2-backend (или Docker Engine внутри WSL2 + `nvidia-container-toolkit`). GPU прокидывается через `/dev/dxg`, executor определяет это сам.
- **Частоты** из WSL2 не фиксируются: при необходимости `nvidia-smi.exe -lgc 1320,1320` в PowerShell от администратора.
- **TDR Windows** убивает ядра дольше ~2 с на GPU с дисплеем. Для длинных TL нужно увеличить `TdrDelay` в реестре.

Без Docker (systemd на хосте) — те же переменные, что в `compose.yaml`, плюс `JUDGE_HARNESS_DIR`, `JUDGE_PYTOOLS_DIR`, `JUDGE_SECCOMP_POLICY`. nsjail требует root (для user namespace с другим uid).

## Требования к контейнеру executor

Контейнер не привилегированный. nsjail нужны только:

- `cap_add: [SYS_ADMIN]` — создание namespaces и монтирование;
- `security_opt: [seccomp=unconfined, apparmor=unconfined, systempaths=unconfined]` — профили Docker запрещают как раз эти вызовы, а поверх замаскированного `/proc` нельзя смонтировать `/proc` песочницы.

Границей для недоверенного кода остаётся nsjail со своей политикой seccomp: вызовы класса «выход из песочницы» (`mount`, `ptrace`, `bpf`, `setns` и т. п.) убивают процесс, и это видно как `RUNTIME_ERROR` и в логе.

Брокеру нужен `consumer_timeout` выше самого долгого задания (в `compose.yaml` — 4 часа): задание подтверждается только по завершении, и по умолчанию RabbitMQ через 30 минут отдал бы его другому executor.

При остановке executor даёт взятым заданиям доработать (`judge.executor.shutdown-timeout`, 5 минут; `stop_grace_period` в compose — 6 минут): прерванное задание возвращается в очередь, а это идёт в счёт лимита доставок.

Образы зафиксированы по digest, nsjail — по коммиту, OSS CAD Suite — по дате сборки и sha256, Python-пакеты — по точным версиям (`deploy/executor-requirements.txt`, `deploy/executor-emu-requirements.txt`); проверки хешей пакетов (`--require-hashes`) нет. Образ эмуляторов требует того же от контейнера, что и образ GPU: внутри песочницы работают Yosys, Verilator, make и g++.

## Несколько executors

Посылка исполняется только на executor её процессора: у каждого процессора своя очередь `judge.jobs.<processor>`, executor слушает очереди процессоров из своего `JUDGE_PROCESSORS`.

- **`gpu-cuda` — одна GPU.** Времена всех executor `gpu-cuda` сравниваются друг с другом, поэтому у них должна быть одна и та же видеокарта. Executor задаёт её тремя переменными: `JUDGE_GPU_PROFILE` (имя, попадает в `JudgeResult.gpu`), `JUDGE_GPU_ARCH` и `JUDGE_GPU_NAME` (имя устройства, как его сообщает CUDA). При старте он сверяет их с реальной картой и не запускается при расхождении. Gateway пишет ERROR, если executors одного процессора сообщают разные модели.
- **Вторая машина:** `judge/deploy/compose.executor.yaml` поднимает только executor `gpu-cuda` и подключает его к брокеру основной установки (пример команды — в шапке файла). В основной установке процессор должен быть в `JUDGE_PROCESSORS` gateway, брокер открыт (`RABBITMQ_BIND=0.0.0.0`), заданы `RABBITMQ_USER`/`RABBITMQ_PASSWORD`.
- **`JUDGE_EXECUTOR_ID`** должен быть уникальным; gateway пишет ERROR, если один id пришёл с двух хостов.
- **Нет живого executor процессора:** посылка принимается и ждёт в очереди; gateway пишет WARN и считает `judge.gateway.submissions_without_executor`. Число живых executor по процессорам — `judge.gateway.alive_executors` и `GET /api/v1/queues`.
- **GPU вышла из строя:** executor перестаёт брать посылки `gpu-cuda`, пока проба не пройдёт снова; посылки остаются в очереди для других executor этого процессора. Очереди других процессоров того же executor это не затрагивает.
- **Один `submissionId` для двух процессоров или языков** в пределах окна дедупликации отклоняется (400).

### Обновление со схемы GPU-профилей

До процессоров очереди назывались по GPU-профилю (`judge.jobs.rtx3060`), задание несло `gpuProfile`, а gateway настраивался `JUDGE_GPU_PROFILES`. Новые gateway и executor такие задания не читают, а старые очереди сами не исчезают. Порядок обновления:

1. Остановить приём посылок (бэкенд или gateway).
2. Дождаться, пока старые очереди `judge.jobs.<profile>` опустеют (`GET /api/v1/queues` или UI RabbitMQ).
3. Обновить gateway, executors и бэкенд; в окружении заменить `JUDGE_GPU_PROFILES`/`JUDGE_DEFAULT_GPU_PROFILE` на `JUDGE_PROCESSORS`.
4. Удалить старые очереди: `rabbitmqctl delete_queue judge.jobs.rtx3060` (для каждого бывшего профиля).

## Mock executor: интеграционные тесты без GPU

Для проверки связки бэкенд → gateway → очередь → события на машине без видеокарты (CI, ноутбук):

```bash
docker compose --profile mock up -d --wait --build
```

Поднимаются RabbitMQ, gateway и `judge-executor-mock` — тот же сервис executor в Spring-профиле `mock`, в лёгком образе без CUDA, PyTorch и nsjail (~0.5 ГБ вместо ~27 ГБ). Он занимает место настоящего executor: слушает очереди своих процессоров (`JUDGE_PROCESSORS`, по умолчанию `gpu-cuda`; годится любой процессор каталога, в том числе ещё не реализованный), шлёт heartbeat, обрабатывает отмену и публикует ту же последовательность событий (`COMPILING` → `TESTING k/n` → `FINISHED`). Код при этом не компилируется и не запускается.

Вердикт задаётся комментарием в исходнике посылки; без него — `ACCEPTED`:

```cpp
// judge-mock: verdict=WRONG_ANSWER test=2 score=3.5 tests=5
```

- `verdict` — любой из вердиктов судьи. `COMPILATION_ERROR`, `POLICY_VIOLATION`, `INTERNAL_ERROR`, `CANCELLED` завершают посылку до тестов; остальные падают на тесте `test` (по умолчанию 1), предыдущие тесты — `ACCEPTED`.
- `score` — score принятых тестов в единицах процессора посылки (по умолчанию 1.0); `time-ms` — прежнее имя того же ключа.
- `tests` — сколько тестов у задачи (по умолчанию 3): mock не запускает генератор и не знает настоящего списка. Тест 1 — открытый `sample`, остальные `test-2`, `test-3`, … закрытые.
- Опечатка в комментарии (неизвестный ключ или вердикт) даёт `INTERNAL_ERROR` с пояснением, а не `ACCEPTED`.
- Задача и посылка валидируются так же, как на настоящем executor, включая проверку процессора и языка.
- Пауза после каждого теста — `judge.executor.mock.test-delay` (20 мс).

Не запускайте mock рядом с настоящим executor того же процессора: они будут делить одну очередь. В результатах mock виден по `processor.version = "mock (no compiler, no GPU, no emulator)"` и, у `gpu-cuda`, `gpu.name = "Mock GPU"`.

## Известные ограничения и что обсудить

- **Время сейчас подделывается.** Оно меряется внутри процесса решения и приходит строкой статус-канала, которую решение может написать само; обвязку можно обойти и иначе (подмена символов, фоновый поток, запоминание результата между прогонами). Проверяется снаружи только корректность ответа. Сторож ограничивает запуск сверху, но не подтверждает сообщённое время. **До внешнего замера на время нельзя опираться в рейтинге.**
- **`GPU_KERNELS` не считает CPU-часть.** Поэтому по умолчанию время — весь вызов `solve` (`WALL`), а `GPU_KERNELS` включается только явно в задаче.
- **Повторы в серой зоне обходятся запоминанием ответа** (решено оставить медиану): решение со статическим кэшем проходит при времени первого прогона до 1.25·TL. То же с прогревом на первом тесте.
- **Фоновый поток.** Решение может запустить поток в первом вызове `solve` и считать следующие тесты, пока обвязка их готовит: входы всех тестов смонтированы на весь запуск.
- **Запреты на заголовки, asm и символы — рекомендательные, а не граница безопасности.** `asm` ищется по исходнику и собирается склейкой токенов в макросах; с host-asm доступны прямые системные вызовы, и запрет `fork`/`dlopen` по именам символов теряет смысл. В песочницу запуска смонтирован весь `/usr`, включая cuBLAS и cuDNN из `/usr/local/cuda`, так что «других CUDA-библиотек нет» верно только для линковки. Граница безопасности одна: nsjail + seccomp. Ограничения на инструкции GPU надёжно задаются через `forbidden-sass`.
- **Дедупликация в gateway — в памяти одного инстанса.** Если gateway будет несколько, нужен общий storage (Redis/БД бэкенда).
- **Проверка корректности самой задачи** (что тесты ловят неверные решения, допуски не пропускают fp16) — обсудим отдельно.
- **Ошибки в `tests()` видны только на executor.** Тесты объявляет генератор, а его запускает только executor, поэтому `POST /api/v1/problems/validate` проверяет лишь JSON задачи. Задачу с неверным списком тестов первая посылка увидит как `INTERNAL_ERROR`; ловить такие ошибки нужно прогоном `judge_problem.prepare` в CI репозитория задач.
