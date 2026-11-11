# Архитектура

UML-диаграммы текущего состояния проекта. Подробности — в [README](../README.md),
[docs/backend/submissions.md](backend/submissions.md) и [judge/README.md](../judge/README.md).

## Развёртывание

Сервисы описаны в корневом `compose.yaml`. Без профилей стартует только PostgreSQL; `app` поднимает backend
и frontend, `judge` — RabbitMQ и gateway, `gpu` или `mock` добавляют исполнителя.

```mermaid
flowchart TB
  browser["Браузер"]
  subgraph platform["Платформа (профиль app)"]
    frontend["frontend<br/>nginx + React SPA, :3000"]
    backend["backend<br/>Spring Boot 4, :8080"]
  end
  postgres[("PostgreSQL 18<br/>схема Flyway, JPA")]
  repo["Репозиторий задач<br/>lottery7/GpuCodeforcesProblems"]
  subgraph judge["Тестирующая система (профили judge, gpu, emu, cuda-sim, mock)"]
    gateway["judge-gateway<br/>Spring Boot, :8090"]
    rabbit[["RabbitMQ 4.1<br/>jobs, events, heartbeats, control"]]
    executor["judge-executor<br/>по одному на машину,<br/>очередь на каждый свой процессор"]
    emu["judge-executor-emu<br/>тот же сервис без GPU:<br/>rtl (Yosys + Verilator),<br/>riscv-rvv (QEMU + плагин)"]
    cudasim["judge-executor-cuda-sim<br/>тот же сервис без GPU:<br/>cuda-sim (GPGPU-Sim, QV100)"]
    mock["judge-executor-mock<br/>вердикт из комментария"]
    sandbox["nsjail + процессор<br/>gpu-cuda: nvcc и NVIDIA GPU<br/>эмуляторы: по процессу на тест"]
  end
  browser -->|HTTP| frontend
  frontend -->|"/api, /admin"| backend
  backend -->|JDBC| postgres
  backend -->|"git clone, pull (JGit)"| repo
  backend -->|"POST /api/v1/submissions"| gateway
  gateway -->|"judge.jobs.&lt;processor&gt;"| rabbit
  rabbit -->|judge.events| backend
  rabbit <-->|"задания, события"| executor
  rabbit <-->|"judge.jobs.rtl, .riscv-rvv"| emu
  rabbit <-->|"judge.jobs.cuda-sim"| cudasim
  rabbit <-->|"профиль mock"| mock
  executor --> sandbox
  emu --> sandbox
  cudasim --> sandbox
```

Служебные обмены RabbitMQ: `judge.heartbeats` (executor → gateway, мониторинг живых исполнителей),
`judge.control` (отмена посылки), `judge.jobs.dead` (задание упало 5 раз — gateway публикует `INTERNAL_ERROR`).

## Дуэли

Поиск соперника и приглашения хранятся отдельно от начавшейся комнаты. Общая блокировка PostgreSQL
сериализует подбор, принятие и отмену. Уведомления доступны только своему пользователю.

```mermaid
sequenceDiagram
  participant A as Инициатор
  participant B as backend
  participant DB as PostgreSQL
  participant C as Соперник
  A->>B: POST /api/duels (темы и способ поиска)
  B->>DB: Записать поиск или приглашение
  alt Случайный соперник
    C->>B: POST /api/duels (случайный поиск)
    B->>DB: Найти совместимый поиск под блокировкой
  else Приглашение по нику
    C->>B: GET /api/notifications
    C->>B: POST /api/duels/{id}/accept
  end
  B->>DB: Выбрать задачу, создать комнату и две регистрации
  B->>DB: Уведомления о начале обоим участникам
  B-->>A: matched и contestId
  B-->>C: matched и contestId
```

## Путь посылки

Обычные задачи и оптимизация используют путь через judge. Исходный код оптимизации и оба варианта
угадывания заранее проверяются и измеряются отдельными заданиями judge. Ответ угадывания
проверяется по готовому поколению замеров и сохраняет неизменяемый снимок результата.
Начавшаяся дуэль использует тот же контест; оба участника уже зарегистрированы при подборе.
Правила форматов: [backend/formats.md](backend/formats.md).

```mermaid
sequenceDiagram
  participant U as Браузер
  participant B as backend
  participant DB as PostgreSQL
  U->>B: GET /api/problems/{id}
  B-->>U: Два кода без ответа и времен
  U->>B: POST /api/problems/{id}/guess
  B->>DB: Проверить готовность замеров и контекст
  B->>DB: Сохранить ответ и снимок времен текущего поколения
  DB-->>B: Посылка accepted или wrong_answer
  B-->>U: Свой ответ, времена и объяснение
  U->>B: POST /api/problems/{id}/comparisons
  B->>DB: Проверить две свои посылки и сохранить задачу guess
  B-->>U: Новая задача в банке
```

```mermaid
sequenceDiagram
  participant B as backend
  participant DB as PostgreSQL
  participant G as judge-gateway
  participant E as judge-executor
  B->>G: GET /api/v1/executors
  G-->>B: Живые исполнители (для gpu-cuda - GPU и драйвер)
  B->>DB: Найти задания версии и отпечатка среды
  B->>DB: Создать недостающие задания исходных кодов
  loop Каждый исходный код
    B->>G: POST /api/v1/submissions с reference ID
    G->>E: Код, процессор и полные тесты
    E-->>B: События judge через RabbitMQ
    B->>DB: Сохранить проверенный замер и оборудование
  end
  B->>DB: Все варианты готовы - открыть поколение
  Note over B,DB: Новые тесты, процессор или GPU создают другие задания
```

Подробности восстановления и актуальности: [reference-measurements.md](backend/reference-measurements.md).

```mermaid
sequenceDiagram
  participant U as Браузер (SPA)
  participant B as backend
  participant DB as PostgreSQL
  participant G as judge-gateway
  participant MQ as RabbitMQ
  participant E as judge-executor
  U->>B: POST /api/submissions
  B->>DB: insert Submission (queued)
  B-->>U: Submission, status queued
  B->>G: POST /api/v1/submissions
  Note over B,G: SubmissionRequest: код + ProblemSpec + processor + language, повтор каждые 15 с
  G->>G: SpecValidator (процессор, язык, лимит), дедупликация
  G->>MQ: JudgeJob в judge.jobs.#lt;processor#gt;
  G-)MQ: JudgeEvent QUEUED
  G-->>B: 202 Accepted, jobId
  B->>DB: сохранить job_id
  MQ->>E: JudgeJob (prefetch = 1)
  E->>E: ProcessorBackend по job.processor
  E->>E: COMPILING: сборка (nvcc у gpu-cuda), проверки политики
  E->>E: PREPARING: генерация тестов процессора
  E->>E: TESTING k/n: nsjail на GPU или в эмуляторе
  E-)MQ: JudgeEvent на каждом этапе
  E-)MQ: FINISHED + JudgeResult (score с типом), затем ack
  MQ-)B: judge.events
  B->>DB: применить по (attempt, seq)
  loop пока статус не окончательный
    U->>B: GET /api/submissions/{id}
    B-->>U: статус, passedTests/totalTests
  end
```

## Состояния посылки

`SubmissionStatus` в backend и события судьи, которые его меняют.

```mermaid
stateDiagram-v2
  direction TB
  state "wrong_answer, time_limit, memory_limit, runtime_error" as failed
  [*] --> queued : POST /api/submissions
  queued --> compiling : COMPILING, PREPARING
  compiling --> checking : TESTING k/n
  checking --> accepted : ACCEPTED
  checking --> failed : вердикт теста
  compiling --> compile_error : COMPILATION_ERROR, POLICY_VIOLATION
  queued --> system_error : gateway 400/413, таймаут 10 мин
  compiling --> system_error : INTERNAL_ERROR, CANCELLED
  checking --> system_error : INTERNAL_ERROR, CANCELLED
  system_error --> queued : recheck (новый id.rN)
  accepted --> [*]
  failed --> [*]
  compile_error --> [*]
```

## Модель данных

JPA-сущности backend, таблицы схемы `gpucodeforces`.

```mermaid
classDiagram
  direction LR
  class User {
    UUID id
    String handle
    String email
    String passwordHash
  }
  class UserRating {
    String processor
    Int rating
    Int rank
    Int ratingDelta
  }
  class Session {
    UUID token
    Instant expiresAt
  }
  class Contest {
    UUID id
    String title
    Instant startAt
    Instant endAt
    String processor
    String format
    UUID challengerId
    UUID opponentId
    Instant hackEndAt
    Instant finalizedAt
    Instant ratingAppliedAt
    phaseAt(now) ContestPhase
    hacksOpen(now) Boolean
  }
  class VirtualParticipation {
    UUID id
    Instant startedAt
    Instant endsAt
  }
  class Hack {
    UUID id
    String problemId
    String testJson
    String testHash
    String status
    String jobId
    Int retryCount
  }
  class TestSuite {
    String id
    String testsJson
  }
  class JudgingRun {
    Int runNumber
    String status
    BigDecimal score
    String testSuite
  }
  class ContestProblem {
    Int position
  }
  class ContestRegistration
  class DuelRequest {
    UUID id
    String mode
    String processor
    String status
    String[] topics
    Instant expiresAt
  }
  class ActiveDuel
  class Notification {
    String type
    Instant createdAt
    Instant readAt
  }
  class RatingHistory {
    String processor
    Int rank
    Int ratingDelta
    Int ratingAfter
  }
  class Problem {
    String id
    String source
    Instant createdAt
  }
  class ProblemVersion {
    UUID id
    String code
    String title
    String difficulty
    Boolean draft
    String statementMarkdown
    List~String~ processors
    Map starters
    String format
    JSON formatData
    String judgeSpec
    String contentHash
    String judgeHash
    String revision
  }
  class Submission {
    UUID id
    String contextKey
    String testSuite
    String code
    String guessChoice
    String guessBenchmarkKey
    JSON guessResult
    SubmissionStatus status
    String processor
    String language
    BigDecimal score
    ScoreType scoreType
    Map metrics
    JSON measurement
    Int passedTests
    Int totalTests
    String judgeHash
    String benchmarkEnvironmentKey
    String jobId
    Int eventAttempt
    Int eventSeq
    Int recheckCount
  }
  class ProcessorTests {
    String judgeHash
    String processor
    List tests
  }
  class ReferenceJob {
    UUID id
    UUID problemVersionId
    String environmentKey
    String role
    String processor
    String language
    String status
    Int generation
    BigDecimal score
    ScoreType scoreType
    JSON measurement
    String jobId
  }
  ProblemVersion "1" -- "*" ReferenceJob
  Session "*" --> "1" User
  User "1" *-- "*" UserRating : ratings
  DuelRequest "*" --> "1" User : creator
  DuelRequest "*" --> "0..1" User : opponent
  DuelRequest "*" --> "0..1" Contest
  ActiveDuel "0..1" --> "1" User
  ActiveDuel "*" --> "0..1" DuelRequest
  Notification "*" --> "1" User
  Notification "*" --> "1" DuelRequest
  Contest "*" --> "*" User : authors
  ContestRegistration "*" --> "1" Contest
  ContestRegistration "*" --> "1" User
  ContestProblem "*" --> "1" Contest
  ContestProblem "*" --> "1" Problem
  ContestProblem "*" --> "0..1" ProblemVersion : pinned
  RatingHistory "*" --> "1" Contest
  RatingHistory "*" --> "1" User
  Problem "1" *-- "*" ProblemVersion : versions
  Problem "1" --> "0..1" ProblemVersion : current
  Submission "*" --> "1" User
  Submission "*" --> "1" ProblemVersion
  Submission "*" --> "0..1" Contest
  Submission "*" --> "1" TestSuite
  VirtualParticipation "*" --> "1" Contest
  VirtualParticipation "*" --> "1" User
  Hack "*" --> "1" Contest
  Hack "*" --> "1" User
  Hack "*" --> "1" Submission : target
  JudgingRun "*" --> "1" Submission
  JudgingRun "*" --> "1" TestSuite
```

`contextKey` посылки — где она засчитана: `practice`, ID контеста (официальное участие), `upsolve:<контест>`
или `virtual:<попытка>`; правила — в [backend/participation.md](backend/participation.md).

## Компоненты backend

Контроллеры реализуют интерфейсы, сгенерированные из [docs/frontend/openapi.yaml](frontend/openapi.yaml).
Репозитории Spring Data, фильтры безопасности и мапперы опущены.

```mermaid
classDiagram
  direction LR
  namespace auth {
    class SessionController
    class SessionIssuer
    class SessionCookieAuthenticationFilter
  }
  namespace user {
    class UsersController
    class UserService
    class ProfileService
  }
  namespace problem {
    class ProblemsController
    class ProblemService
    class ProblemCatalog
    class ProblemListing {
      page(context, filter) ProblemPage
    }
    class ComparisonService
    class ProblemSyncService {
      sync() SyncResult
    }
    class ProblemPackageReader
    class ProblemEnvironments {
      markdown(spec, judgeHash, processor)
      recordTests()
    }
  }
  namespace processor {
    class ProcessorsController
    class ProcessorCatalog {
      enabled
      require(id)
    }
  }
  namespace contest {
    class ContestsController
    class ContestService
    class VirtualController
    class VirtualParticipationService {
      start(contestId, user)
    }
  }
  namespace duel {
    class DuelsController
    class DuelService
  }
  namespace notification {
    class NotificationsController
    class NotificationService
  }
  namespace submission {
    class GuessService
    class SubmissionsController
    class SubmissionService {
      submit() Submission
      recheck() Submission
      apply(JudgeEvent)
      resendUnsent()
      reportStuck()
      rejudgeExpandedSuites()
    }
    class HacksController
    class HackService {
      create() Hack
      suite(contestId, problemId) String
      apply(JudgeEvent)
    }
    class SubmissionStats
    class ExampleChecks {
      check() ExampleOutcome
      complete(JudgeResult)
    }
    class JudgeGateway {
      send(SubmissionRequest) SendOutcome
    }
    class JudgeEventListener {
      onEvent(Message)
    }
  }
  namespace benchmark {
    class ReferenceMeasurements {
      refresh()
      view(ProblemVersion) ReferenceMeasurementView
      apply(JudgeEvent) Boolean
    }
    class ReferenceEnvironmentMonitor
  }
  namespace rating {
    class ContestRatingFinalizer {
      finalizeDueContests()
    }
    class RatingFinalizationService
    class RatingCalculator
  }
  namespace admin {
    class AdminController
    class GroovyConsole
  }
  SessionController ..> SessionIssuer
  DuelsController ..> DuelService
  NotificationsController ..> NotificationService
  DuelService ..> ProblemCatalog
  UsersController ..> UserService
  UsersController ..> ProfileService
  UsersController ..> SessionIssuer
  ProfileService ..> UserService
  ProfileService ..> SubmissionStats
  ProblemsController ..> ProblemService
  ProblemsController ..> ProblemListing
  ProblemService ..> VirtualParticipationService
  ContestsController ..> VirtualParticipationService
  VirtualController ..> VirtualParticipationService
  VirtualParticipationService ..> ContestService
  HacksController ..> HackService
  HackService ..> ProblemCatalog
  HackService ..> JudgeGateway
  SubmissionService ..> HackService
  JudgeEventListener ..> HackService
  RatingFinalizationService ..> HackService
  ProblemsController ..> SubmissionStats
  ProblemsController ..> ExampleChecks
  ProblemsController ..> ProblemEnvironments
  ProblemsController ..> GuessService
  ProblemsController ..> ComparisonService
  ProblemsController ..> ReferenceMeasurements
  GuessService ..> ReferenceMeasurements
  ReferenceMeasurements ..> ReferenceEnvironmentMonitor
  ReferenceMeasurements ..> JudgeGateway
  ReferenceMeasurements ..> ProblemCatalog
  GuessService ..> ProblemService
  GuessService ..> ProblemCatalog
  GuessService ..> SubmissionService
  ProblemService ..> ProblemCatalog
  ProblemService ..> ContestService
  ProblemService ..> ProcessorCatalog
  ProcessorsController ..> ProcessorCatalog
  ProblemEnvironments ..> ProcessorCatalog
  ContestService ..> ProcessorCatalog
  SubmissionService ..> ProblemEnvironments
  UsersController ..> ProcessorCatalog
  ProblemSyncService ..> ProblemPackageReader
  ContestsController ..> ContestService
  ContestService ..> ProblemCatalog
  ContestService ..> SubmissionStats
  SubmissionsController ..> SubmissionService
  SubmissionService ..> ProblemService
  SubmissionService ..> ContestService
  SubmissionService ..> SubmissionStats
  SubmissionService ..> JudgeGateway
  SubmissionService ..> ReferenceMeasurements
  JudgeEventListener ..> ReferenceMeasurements
  ExampleChecks ..> ProblemCatalog
  ExampleChecks ..> JudgeGateway
  JudgeEventListener ..> SubmissionService
  JudgeEventListener ..> ExampleChecks
  ContestRatingFinalizer ..> RatingFinalizationService
  RatingFinalizationService ..> ContestService
  RatingFinalizationService ..> RatingCalculator
  AdminController ..> GroovyConsole
  AdminController ..> ProblemSyncService
```

Фоновые задачи: синхронизация банка задач с репозиторием раз в минуту (`ProblemSyncService`), повторная
отправка непринятых посылок каждые 15 секунд и поиск зависших раз в минуту (`SubmissionService`), повторная
отправка взломов (`HackService`) и перетестирование принятых решений на тестах успешных взломов
(`SubmissionService.rejudgeExpandedSuites`) каждые 15 секунд, финализация результатов и рейтинга завершившихся
контестов раз в минуту (`ContestRatingFinalizer`, по процессорам: рейтинг каждого процессора свой).

## Компоненты тестирующей системы

`judge-api` — общие контракты gateway, executor и backend, включая каталог процессоров. У `JobProcessor` две
реализации: настоящий конвейер и mock (Spring-профиль `mock`). Конвейер выбирает `ProcessorBackend` по процессору
задания; `GpuCudaBackend` — путь на реальной GPU, `RtlBackend` — схема в Verilator, `RiscvBackend` — RISC-V в QEMU с плагином судьи, `CudaSimBackend` — CUDA в GPGPU-Sim. Эмулируемые процессоры
наследуют `EmulatorBackend`: в нём общий порядок проверки (сборка, тесты задачи, работник из пула) и цикл по тестам
`EmulatorRun`, а процессор задаёт только сборку посылки и запуск одного теста. Сборки эмуляторов хранит общий
`BuildCache` (каталог на ключ, ошибки сборки тоже кэшируются). `HarnessRunner` разбирает статус-строки обвязки
`gpu-cuda` в `HarnessLine` и сам решает, могла ли обвязка написать строку в этот момент. Конфигурация и
утилиты опущены.

```mermaid
classDiagram
  direction LR
  namespace judge_api {
    class SubmissionRequest
    class JudgeJob
    class JudgeEvent {
      String jobId
      Int attempt
      Int seq
      SubmissionStatus status
    }
    class JudgeResult {
      Verdict verdict
      Score score
      ProcessorRun processor
    }
    class ProblemSpec
    class ProcessorSpec
    class Processors {
      List~ProcessorInfo~ all
    }
    class RtlSignature
    class SpecValidator
    class ExecutorHeartbeat
    class ControlMessage
  }
  namespace judge_gateway {
    class SubmissionController
    class ProblemController
    class MonitoringController
    class SubmissionService {
      submit(SubmissionRequest) SubmissionAccepted
      cancel(submissionId)
    }
    class ExecutorRegistry
    class JudgeMessaging
    class DeadJobListener
  }
  namespace judge_executor {
    class JobListener
    class JobProcessor {
      <<interface>>
    }
    class JudgePipeline
    class MockJudge
    class ProcessorBackend {
      <<interface>>
      judge(JudgeJob, runDir, JudgeHooks) JudgeResult
    }
    class GpuCudaBackend
    class RtlBackend
    class RtlBuilder
    class RtlTestbench
    class RiscvBackend
    class RiscvBuilder
    class RiscvHarness
    class CudaSimBackend
    class EmulatorBackend {
      <<abstract>>
      build(JudgeJob, ProcessorSpec) EmulatorBuild
      launch(TestRun, artifact) SimulatorLaunch
    }
    class EmulatorRun
    class BuildCache
    class SlotPool
    class Builder
    class ProblemStore
    class HarnessRunner
    class HarnessLine {
      <<sealed>>
      parse(String) HarnessLine
    }
    class OutputChecker
    class GpuManager
    class Sandbox {
      <<interface>>
    }
    class NsjailSandbox
    class DirectSandbox
    class EventPublisher
    class ControlListener
    class CancelRegistry
    class HeartbeatPublisher
  }
  SubmissionRequest *-- ProblemSpec
  JudgeJob *-- ProblemSpec
  ProblemSpec *-- ProcessorSpec
  SpecValidator ..> Processors
  SpecValidator ..> RtlSignature
  JudgeEvent *-- JudgeResult
  SubmissionController ..> SubmissionService
  ProblemController ..> SpecValidator
  MonitoringController ..> ExecutorRegistry
  SubmissionService ..> SpecValidator
  SubmissionService ..> JudgeMessaging
  SubmissionService ..> ExecutorRegistry
  DeadJobListener ..> JudgeMessaging
  JudgeMessaging ..> JudgeJob : publishes
  JudgeMessaging ..> ControlMessage : publishes
  ExecutorRegistry ..> ExecutorHeartbeat : consumes
  JobListener ..> JobProcessor
  JobListener ..> JudgeJob : consumes
  JudgePipeline ..|> JobProcessor
  MockJudge ..|> JobProcessor
  JudgePipeline ..> ProcessorBackend
  GpuCudaBackend ..|> ProcessorBackend
  GpuCudaBackend ..> Builder
  GpuCudaBackend ..> ProblemStore
  GpuCudaBackend ..> HarnessRunner
  HarnessRunner ..> HarnessLine
  GpuCudaBackend ..> GpuManager
  GpuManager ..> SlotPool
  EmulatorBackend ..|> ProcessorBackend
  EmulatorBackend ..> ProblemStore
  EmulatorBackend ..> EmulatorRun
  EmulatorBackend ..> SlotPool : работники
  RtlBackend --|> EmulatorBackend
  RtlBackend ..> RtlBuilder
  RtlBuilder ..> RtlTestbench
  RtlBuilder ..> BuildCache
  RtlBuilder ..> Sandbox
  RiscvBackend --|> EmulatorBackend
  RiscvBackend ..> RiscvBuilder
  RiscvBuilder ..> RiscvHarness
  RiscvBuilder ..> BuildCache
  RiscvBuilder ..> Sandbox
  CudaSimBackend --|> EmulatorBackend
  EmulatorRun ..> Sandbox
  EmulatorRun ..> OutputChecker
  ProblemStore ..> SlotPool : GPU-слот, если есть GPU
  JudgePipeline ..> EventPublisher
  JudgePipeline ..> CancelRegistry
  MockJudge ..> EventPublisher
  Builder ..> Sandbox
  ProblemStore ..> Sandbox
  HarnessRunner ..> Sandbox
  HarnessRunner ..> OutputChecker
  NsjailSandbox ..|> Sandbox
  DirectSandbox ..|> Sandbox
  ControlListener ..> CancelRegistry
  HeartbeatPublisher ..> JobProcessor : статусы процессоров
  HeartbeatPublisher ..> EventPublisher
  EventPublisher ..> JudgeEvent : publishes
```
