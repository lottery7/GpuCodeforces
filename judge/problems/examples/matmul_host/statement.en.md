---
title: Matrix Multiplication from CPU Memory
---

You are given matrices `A` of size `M×K` and `B` of size `K×N` of numbers of type `float`, stored row by row.
Write their product of size `M×N` to `C`.

```cpp
void solve(const float* A, const float* B, float* C, int M, int N, int K);
```

All three arrays are **in CPU memory**: copying to the GPU and back is part of the measured time.
Tensor cores (`HMMA`) are not allowed.

## Example

`M = 2, N = 2, K = 3`, `A = [[1, 2, 3], [4, 5, 6]]`, `B = [[1, 0], [0, 1], [1, 1]]`,
the answer is `C = [[4, 5], [10, 11]]`.

## Constraints

- 1 ≤ M, N, K ≤ 2048;
- the comparison tolerance is `1e-3`.
