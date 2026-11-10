def solve(inputs):
    a = inputs["A"].cuda()  # M x K CUDA tensor
    b = inputs["B"].cuda()  # K x N CUDA tensor
    m = int(inputs["M"])
    k = int(inputs["K"])
    n = int(inputs["N"])

    a = a[:m, :k]
    b = b[:k, :n]

    return {"C": a @ b}