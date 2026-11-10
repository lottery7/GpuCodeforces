from collections.abc import Iterator

import numpy as np


def generate_tests(seed: int) -> Iterator[dict]:
    rng = np.random.default_rng(seed)

    n = 1024
    a = rng.random(n, dtype=np.float32)
    b = rng.random(n, dtype=np.float32)

    yield {
        "inputs": {
            "A": a,
            "B": b,
            "N": np.array(n, dtype=np.int32),
        },
    }

    n = 1_000_000
    a = rng.random(n, dtype=np.float32)
    b = rng.random(n, dtype=np.float32)

    yield {
        "inputs": {
            "A": a,
            "B": b,
            "N": np.array(n, dtype=np.int32),
        },
    }