import torch


def tests():
    yield {"name": "sample", "sample": True, "scalars": {"N": 4},
           "inputs": {"A": [1.0, 2.0, 3.0, 4.0], "B": [5.0, 6.0, 7.0, 8.0]}}
    yield {"name": "one", "scalars": {"N": 1}, "seed": 1}
    yield {"name": "odd", "scalars": {"N": 1000003}, "seed": 2}
    yield {"name": "large", "scalars": {"N": 25000000}, "seed": 3}
    yield {"name": "max", "scalars": {"N": 100000000}, "seed": 4}


def generate(test, gen, device):
    n = test["N"]
    return {
        "A": torch.empty(n, device=device).uniform_(-1000.0, 1000.0, generator=gen),
        "B": torch.empty(n, device=device).uniform_(-1000.0, 1000.0, generator=gen),
    }


def reference(test, inputs):
    return {"C": inputs["A"] + inputs["B"]}


def validate_input(test, inputs):
    return 1 <= test["N"] <= 100000000 and all(
        bool(torch.isfinite(value).all() and (value.abs() <= 1000).all())
        for value in inputs.values()
    )
