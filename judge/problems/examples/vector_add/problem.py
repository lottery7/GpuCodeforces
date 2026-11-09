import torch


def generate(test, gen, device):
    n = test["N"]
    return {
        "A": torch.empty(n, device=device).uniform_(-1000.0, 1000.0, generator=gen),
        "B": torch.empty(n, device=device).uniform_(-1000.0, 1000.0, generator=gen),
    }


def reference(test, inputs):
    return {"C": inputs["A"] + inputs["B"]}
