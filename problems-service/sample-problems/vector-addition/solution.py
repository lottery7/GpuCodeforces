def solve(inputs):
    a = inputs["A"].cuda()  # CUDA tensor
    b = inputs["B"].cuda()  # CUDA tensor
    n = int(inputs["N"])

    return {"C": (a + b)[:n]}