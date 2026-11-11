import torch


def tests():
    yield {"name": "sample", "sample": True, "scalars": {"N": 4},
           "inputs": {"A": [1, -2, 2147483647, 0], "B": [5, 6, 1, -1]}}
    yield {"name": "one", "scalars": {"N": 1}, "seed": 1}
    yield {"name": "odd", "scalars": {"N": 1001}, "seed": 2}
    yield {"name": "large", "scalars": {"N": 4096}, "seed": 3}
    # correctness only: its cycles are neither limited by nor counted in the score
    yield {"name": "long", "scalars": {"N": 20000}, "seed": 4, "measure": False}


def generate(test, gen, device):
    n = test["N"]
    low, high = -2**31, 2**31
    return {
        "A": torch.randint(low, high, (n,), dtype=torch.int32, device=device, generator=gen),
        "B": torch.randint(low, high, (n,), dtype=torch.int32, device=device, generator=gen),
    }


def reference(test, inputs):
    # int32 addition wraps around, like the 32-bit adder of a circuit
    return {"C": inputs["A"] + inputs["B"]}
