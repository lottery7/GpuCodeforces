from collections.abc import Iterator

import numpy as np


def generate_tests(seed: int) -> Iterator[dict]:
    rng = np.random.default_rng(seed)

    m, k, n = 32, 32, 32
    yield _case(rng, m, k, n)

    m, k, n = 256, 256, 256
    yield _case(rng, m, k, n)


def _case(rng, m, k, n):
    return {
        "inputs": {
            "A": rng.random((m, k), dtype=np.float32),
            "B": rng.random((k, n), dtype=np.float32),
            "M": np.array(m, dtype=np.int32),
            "K": np.array(k, dtype=np.int32),
            "N": np.array(n, dtype=np.int32),
        },
    }