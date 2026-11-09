# Формат задачи

Задача — это JSON `ProblemSpec` (см. `judge-api/.../ProblemSpec.kt`), который бэкенд передаёт в каждой посылке, плюс исходник генератора на Python. Пример: `problems/examples/vector_add/` (`problem.json` + `problem.py`; `tools/judge_cli.py` склеивает их в один `ProblemSpec`).

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
  "tests": [
    {"name": "sample", "sample": true, "scalars": {"N": 4}, "data": {"A": [1, 2, 3, 4]}},
    {"name": "big", "scalars": {"N": 100000000}, "seed": 4}
  ],
  "generator": {"language": "PYTHON", "source": "<содержимое problem.py>"},
  "checker": {"mode": "TOLERANCE", "rtol": 1e-5, "atol": 1e-6},
  "limits": {"timeLimitMs": 20, "wallTimeLimitMs": null, "memoryLimitMb": 256, "gpuMemoryLimitMb": 256},
  "timing": {"mode": null, "warmupRuns": 1, "maxRuns": 3},
  "policy": {"allowHeaders": [], "forbidHeaders": [], "forbidSass": [], "allowSass": [], "allowInlineAsm": null}
}
```

### Параметры

| Поле | Значения |
|---|---|
| `type` | `FLOAT16` (`__half`), `BFLOAT16` (`__nv_bfloat16`), `FLOAT32`, `FLOAT64`, `INT8`, `UINT8`, `INT16`, `INT32` (`int`), `UINT32`, `INT64` (`int64_t`), `UINT64`. Скаляры: только 32/64-битные. |
| `role` | `INPUT` → `const T*`; `OUTPUT` → `T*`, проверяется; `INOUT` → `T*`, заполнен входом, проверяется; `SCALAR` → по значению |
| `memory` | `DEVICE` — указатель на память GPU; `HOST` — память CPU. Для массивов обязательно. |
| `size` | число элементов: целочисленное выражение над целыми скалярами (`+ - * / %`, скобки), например `M*K` |

Сигнатура выше превращается в `void solve(const float* A, float* C, int N)` (C++-линковка). Решение с другой сигнатурой получает `COMPILATION_ERROR`.

### Тесты

- Идут по порядку, проверка останавливается на первом упавшем.
- `scalars` — значения всех скаляров. Из них вычисляются размеры массивов.
- `seed` передаётся генератору.
- `data` — явные входы (все `INPUT`/`INOUT`) для ручных тестов, обычно примеров. Для таких тестов генератор не вызывается, эталон — вызывается.
- `sample: true` — тест открытый. Участник увидит детали расхождения и свой stderr.

### Чекер

- `TOLERANCE`: для float `|out − exp| ≤ atol + rtol·|exp|`. NaN совпадает только с NaN, ±Inf — только с тем же Inf. Целые сравниваются точно.
- `EXACT`: побитовое сравнение.

### Время и лимиты

- `timeLimitMs` — лимит на один вызов `solve`, меряется в режиме `timing.mode`. Если режим не задан: `WALL` при всех данных на GPU, `GPU_KERNELS` при данных на CPU (см. README).
- `wallTimeLimitMs` — страховочный wall-лимит на вызов.
- `memoryLimitMb` — RAM решения сверх данных теста.
- `gpuMemoryLimitMb` — память GPU сверх буферов теста. `null` — вся GPU.
- `warmupRuns` — прогревы на первом тесте.
- `maxRuns` — максимум прогонов в серой зоне около TL.

## Генератор (problem.py)

Выполняется один раз на версию задачи на GPU-машине (Python 3.12, PyTorch 2.9.1). Это доверенный код автора задачи.

```python
import torch

def generate(test, gen, device):
    """Входы сгенерированного теста.
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

- Эталон считается в строгом IEEE fp32: TF32 выключен, детерминированные алгоритмы. Для точности можно считать в fp64 и вернуть в нужном типе (см. `matmul_host`).
- Неверные ключи, dtype или размеры — понятная ошибка. Посылка получает `INTERNAL_ERROR` («Tests of this problem could not be generated»), в логе executor есть traceback.
- Локальная проверка генератора (нужен torch):

```bash
PYTHONPATH=pytools python3 -m judge_problem.prepare --spec spec.json --source problem.py --out /tmp/out
```

  Здесь `spec.json` — это `{"signature": ..., "tests": ...}`.
