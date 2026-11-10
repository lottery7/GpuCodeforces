# Формат задачи

Форматы платформы описаны в [docs/backend/formats.md](../../docs/backend/formats.md).
Исходное решение оптимизации и оба варианта угадывания автоматически проходят обычную CUDA-проверку
через судью на тестах задачи. Числа времени не принимаются из `format.json`. Backend использует
результаты этих прогонов; ответ пользователя на угадывание не запускает еще один GPU-прогон.
`format.json`, `first.cu` и `second.cu` не входят в контракт `ProblemSpec`: backend передает
каждый проверяемый исходник как `SubmissionRequest.source`.

Gateway и executor продолжают возвращать времена тестов и `maxTimeMs`. Backend может использовать
`benchmark.aggregation`, чтобы вычислить геометрическое среднее по принятым тестам исходных
решений и пользовательских посылок. Все исходные решения сравниваются на одних тестах и
в одном окружении. Изменение исходников или конфигурации требует новых замеров. Это не меняет
протокол измерения отдельного теста; подробности в документе форматов выше.

Задача — это JSON `ProblemSpec` (см. `judge-api/.../ProblemSpec.kt`), который бэкенд передаёт в каждой посылке, плюс исходник генератора на Python. Списка тестов в JSON нет: тесты объявляет генератор (`tests()`). Пример: `judge/problems/examples/vector_add/` (`problem.json` + `problem.py`; `judge/tools/judge_cli.py` склеивает их в один `ProblemSpec`).

## ProblemSpec

```jsonc
{
  "id": "vector-add",                 // [A-Za-z0-9._-]
  "version": "1",                     // для логов; кэш тестов ключуется хэшем содержимого
  "signature": {
    "function": "solve",
    "params": [
      {"name": "A", "type": "FLOAT32", "role": "INPUT",  "memory": "DEVICE", "size": "N"},
      {"name": "C", "type": "FLOAT32", "role": "OUTPUT", "memory": "DEVICE", "size": "N"},
      {"name": "N", "type": "INT32",   "role": "SCALAR"}
    ]
  },
  "generator": {"language": "PYTHON", "source": "<содержимое problem.py>"},
  "checker": {"mode": "TOLERANCE", "rtol": 1e-5, "atol": 1e-6},
  "limits": {"timeLimitMs": 20, "wallTimeLimitMs": null, "memoryLimitMb": 256, "gpuMemoryLimitMb": 256},
  "timing": {"mode": null, "warmupRuns": 1, "maxRuns": 3},
  "compile": {"arch": "sm_86", "flags": ["-std=c++17", "-O3", "--expt-relaxed-constexpr"]},
  "policy": {"allowHeaders": [], "forbidHeaders": [], "forbidSass": [], "allowSass": [], "allowInlineAsm": null}
}
```

Видеокарта, на которой судится посылка, задаётся рядом с задачей, в `SubmissionRequest.gpuProfile` (например, `rtx3060`): она выбирается на контест, а не на задачу.

### Параметры

| Поле | Значения |
|---|---|
| `type` | `FLOAT16` (`__half`), `BFLOAT16` (`__nv_bfloat16`), `FLOAT32`, `FLOAT64`, `INT8`, `UINT8`, `INT16`, `INT32` (`int`), `UINT32`, `INT64` (`int64_t`), `UINT64`. Скаляры: только 32/64-битные. |
| `role` | `INPUT` → `const T*`; `OUTPUT` → `T*`, проверяется; `INOUT` → `T*`, заполнен входом, проверяется; `SCALAR` → по значению |
| `memory` | `DEVICE` — указатель на память GPU; `HOST` — память CPU. Для массивов обязательно. |
| `size` | число элементов: целочисленное выражение над целыми скалярами (`+ - * / %`, скобки), например `M*K` |

Сигнатура выше превращается в `void solve(const float* A, float* C, int N)` (C++-линковка). Решение с другой сигнатурой получает `COMPILATION_ERROR`.

### Чекер

- `TOLERANCE`: для float `|out − exp| ≤ atol + rtol·|exp|`. NaN совпадает только с NaN, ±Inf — только с тем же Inf. Целые сравниваются точно.
- `EXACT`: побитовое сравнение.

### Время и лимиты

- `timeLimitMs` — лимит на один вызов `solve`, меряется в режиме `timing.mode`. Если режим не задан — `WALL` (весь вызов), где бы ни лежали данные; `GPU_KERNELS` нужно указать явно (см. README).
- `wallTimeLimitMs` — страховочный wall-лимит на вызов.
- `memoryLimitMb` — RAM решения сверх данных теста.
- `gpuMemoryLimitMb` — память GPU сверх буферов теста. `null` — вся GPU.
- `warmupRuns` — прогревы на первом тесте.
- `maxRuns` — максимум прогонов в серой зоне около TL.

### Компиляция и ограничения

- `compile.arch` — архитектура, под которую генерируется код решения (`sm_86`; без PTX и JIT). `null` — архитектура видеокарты профиля. Видеокарта должна уметь исполнять этот код: то же семейство и та же или более ранняя ревизия (`sm_80` на `sm_86` — да; `sm_75` и `sm_89` — нет). Иначе посылка получает `INTERNAL_ERROR` с пояснением.
- `compile.flags` — флаги nvcc для решения, заменяют флаги executor по умолчанию (`null` — оставить их). Разрешены только: `-O0`…`-O3`, `-std=c++11|14|17|20`, `-DИМЯ[=значение]`, `--use_fast_math`, `--fmad=true|false`, `--expt-relaxed-constexpr`, `--expt-extended-lambda`. Любой другой флаг — ошибка валидации (400).
- `policy.forbidSass` / `policy.allowSass` — запрещённые инструкции (опкоды SASS, например `HMMA` для тензорных ядер) поверх политики профиля.
- `policy.allowHeaders` / `policy.forbidHeaders`, `policy.allowInlineAsm` — заголовки и inline asm.

## Генератор (problem.py)

Выполняется один раз на версию задачи на GPU-машине (Python 3.12, PyTorch 2.9.1). Задачи приходят через PR в общий репозиторий, поэтому генератор запускается в той же песочнице, что и решения: сети нет, писать можно только в свою рабочую папку и `/tmp`.

Модуль определяет три функции:

```python
import torch

def tests():
    """Тесты задачи по порядку (список или генератор dict). Проверка останавливается на первом упавшем."""
    # пример из условия: входы и ответ записаны руками
    yield {"name": "sample", "sample": True, "scalars": {"N": 4},
           "inputs": {"A": [1, 2, 3, 4]}, "expected": {"C": [2, 4, 6, 8]}}
    for i, n in enumerate([1, 1000003, 100_000_000]):
        yield {"name": f"random-{n}", "scalars": {"N": n}, "seed": i}

def generate(test, gen, device):
    """Входы теста без рукописных inputs.
    test.index, test.name, test.seed, test.sample, test.scalars (dict), test.sizes (dict: param -> число элементов);
    test["N"] — короткая запись для test.scalars["N"].
    gen — torch.Generator на `device`, засеянный test.seed. Используйте его для детерминизма.
    Вернуть dict: имя -> тензор для КАЖДОГО INPUT/INOUT, dtype как в сигнатуре, test.sizes[name] элементов (форма любая)."""
    n = test["N"]
    return {"A": torch.empty(n, device=device).uniform_(-1, 1, generator=gen)}

def reference(test, inputs):
    """Эталон. inputs — копии входов (менять можно). Вернуть dict для КАЖДОГО OUTPUT/INOUT."""
    return {"C": inputs["A"] * 2}
```

### Тест из `tests()`

| Ключ | Обяз. | Что это |
|---|---|---|
| `name` | да | Уникальное имя, до 128 символов. |
| `scalars` | да | Значения всех скаляров сигнатуры, ровно их. Из них вычисляются размеры массивов. Целые скаляры должны помещаться в свой тип. |
| `sample` | нет | `True` — тест открытый: участник увидит детали расхождения и свой stderr. По умолчанию `False`. |
| `seed` | нет | Зерно для `gen` в `generate()`. По умолчанию — номер теста (с 1). |
| `inputs` | нет | Входы, записанные руками (все `INPUT`/`INOUT`): списки чисел или тензоры. Для такого теста `generate()` не вызывается. |
| `expected` | нет | Ответы, записанные руками (все `OUTPUT`/`INOUT`), только вместе с `inputs`: пример из условия проверяется по тому, что написано в условии, а не по тому, что посчитал эталон. Без `expected` ответ считает `reference()`. |

- Тестов от 1 до 200, данные одного теста — не больше 4 ГБ.
- `tests()` должна давать один и тот же список при каждом запуске: тесты генерирует каждый executor сам. Глобальные `random` и `torch` засеваются нулём перед вызовом, но лучше не использовать случайность в самом списке, а оставить её `generate()` через `seed`.
- `generate()` можно не определять, если у всех тестов есть `inputs`.
- Эталонный ответ не может состоять из одних байтов `0xFF` (NaN для float, −1 или максимум для целых): этим шаблоном обвязка заполняет выходы перед каждым вызовом, и решение, которое ничего не пишет, прошло бы такой тест. Задача с таким тестом отклоняется.
- Gateway проверяет только JSON задачи. Ошибки в `tests()` (неизвестный ключ, не те скаляры, слишком большой тест) видны только при генерации тестов: первая посылка получает `INTERNAL_ERROR` («Tests of this problem could not be generated»), подробности — в логе executor. Проверяйте задачу локально (ниже) или в CI репозитория задач.
- Эталон считается в строгом IEEE fp32: TF32 выключен, детерминированные алгоритмы. Для точности можно считать в fp64 и вернуть в нужном типе (см. `matmul_host`).
- Неверные ключи, dtype или размеры — понятная ошибка. Посылка получает `INTERNAL_ERROR` («Tests of this problem could not be generated»), в логе executor есть traceback.
- Локальная проверка генератора (нужен torch):

```bash
PYTHONPATH=judge/pytools python3 -m judge_problem.prepare --spec spec.json --source problem.py --out /tmp/out
```

  Здесь `spec.json` — это `{"signature": ...}` из задачи. В `/tmp/out/tests.json` будет список тестов, который увидит судья.
