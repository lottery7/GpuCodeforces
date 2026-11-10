# Посылки и проверка

## Путь посылки

1. `POST /api/submissions` сохраняет посылку в статусе `queued` и сразу после коммита отправляет её
   в judge gateway (`POST {JUDGE_GATEWAY_URL}/api/v1/submissions`, `SubmissionRequest` из `judge-api`):
   код, полный `ProblemSpec` версии задачи с генератором и `gpuProfile` (`GPU_PROFILE`).
2. Пока gateway не принял посылку (недоступен, 409, 503), она остаётся в очереди: каждые 15 секунд
   backend отправляет её снова. Gateway дедуплицирует по ID, поэтому повтор безопасен. Если за
   `platform.judge.send-timeout` (10 минут) отправить не удалось или gateway ответил 400/413, посылка
   получает `system_error` и автор может повторить проверку.
3. Судья присылает события асинхронно через RabbitMQ, очередь `judge.events`. Backend объявляет её
   с теми же аргументами, что и gateway, поэтому может стартовать первым.
4. Фронтенд опрашивает `GET /api/submissions/{id}`, пока статус не окончательный.

События приходят не по порядку и могут повторяться. Backend учитывает только задание, которое
вернул gateway (`jobId`), применяет события по возрастанию `(attempt, seq)` и не меняет посылку после
`FINISHED`. Ошибка записи события возвращает его в очередь: `judge.events` не теряет сообщения.

| Судья | Статус посылки |
|---|---|
| `QUEUED` | `queued` |
| `COMPILING`, `PREPARING` | `compiling` |
| `TESTING` (с `progress`) | `checking`, `passedTests`/`totalTests` |
| `ACCEPTED` | `accepted`, время — `maxTimeMs` |
| `WRONG_ANSWER` / `TIME_LIMIT_EXCEEDED` / `MEMORY_LIMIT_EXCEEDED` / `RUNTIME_ERROR` | `wrong_answer` / `time_limit` / `memory_limit` / `runtime_error` |
| `COMPILATION_ERROR`, `POLICY_VIOLATION` | `compile_error` |
| `INTERNAL_ERROR`, `CANCELLED` | `system_error` |

`diagnostic` (только автору) собирается из `message`, `compileLog` и первого упавшего теста;
подробности открытых тестов судья уже ограничил примерами.

## Повторная проверка

Только автор и только после `system_error`/`measurement_error`. Код, контекст, версия задачи и время
отправки сохраняются; судье уходит новый ID `<id>.r<n>`, а события старого задания игнорируются.

## Проверка примера

`POST /api/problems/{id}/example` отправляет решение с генератором, оставляющим только тесты
`sample: true`, и ждёт вердикта до `platform.judge.example-timeout` (2 минуты). Посылка не создаётся.
Ответ приходит через ту же очередь событий, поэтому её должен читать один экземпляр backend.

## Результаты

Принятое время сравнимо только в одной конфигурации проверки: тот же `judge_hash` версии задачи
(содержимое `problem.json` + `problem.py`) и та же GPU. Практика и каждый контест считаются отдельно.

- Банк задач: `solved`, `last*`, `best*` — для пользователя и контекста; `solvedCount` — по всем
  контекстам; `recordTimeMs` — лучшее сравнимое время контекста.
- Лидеры задачи — лучшее сравнимое время каждого пользователя, равные времена делят место.
- Таблица контеста: число решённых задач, затем сумма ускорений `эталон / время`. Эталон — рекорд
  практики этой версии задачи до начала контеста, без него — лучшее время в контесте.
- До 5 непроверенных посылок на пользователя (`platform.judge.max-pending-per-user`).

## Администрирование

Администраторы — пользователи с никами из `ADMIN_HANDLES` (через запятую). Страница `/admin`
выполняет Groovy-скрипты в работающем приложении (`ctx` — Spring `ApplicationContext`, `out` — вывод)
и запускает импорт задач. Это полный доступ к процессу: один скрипт одновременно, таймаут 30 секунд,
в лог пишутся автор и SHA-256 скрипта. API: `POST /api/admin/console`, `POST /api/admin/problems/sync`.

```groovy
ctx.getBean('submissionService').resendUnsent()
```
