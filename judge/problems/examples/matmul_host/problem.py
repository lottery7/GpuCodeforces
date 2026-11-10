import torch


def tests():
    yield {"name": "sample", "sample": True, "scalars": {"M": 2, "N": 2, "K": 3},
           "inputs": {"A": [1, 2, 3, 4, 5, 6], "B": [1, 0, 0, 1, 1, 1]}}
    for seed, (name, m, n, k) in enumerate([("square-small", 64, 64, 64), ("rect", 1000, 777, 333),
                                            ("square-large", 2048, 2048, 2048)], start=1):
        yield {"name": name, "scalars": {"M": m, "N": n, "K": k}, "seed": seed}


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
