import torch


def generate(test, gen, device):
    m, n, k = test["M"], test["N"], test["K"]
    return {
        "A": torch.empty(m * k, device=device).uniform_(-1.0, 1.0, generator=gen),
        "B": torch.empty(k * n, device=device).uniform_(-1.0, 1.0, generator=gen),
    }


def reference(test, inputs):
    m, n, k = test["M"], test["N"], test["K"]
    # fp64 accumulation for the reference, stored as fp32
    a = inputs["A"].view(m, k).double()
    b = inputs["B"].view(k, n).double()
    return {"C": (a @ b).float().reshape(-1)}
