---
title: Vector Addition
---

You are given two vectors `A` and `B` of `N` numbers of type `float`. Write their element-wise sum to `C`:
`C[i] = A[i] + B[i]`.

Implement the function

```cpp
void solve(const float* A, const float* B, float* C, int N);
```

`A`, `B` and `C` point to GPU memory. The function has to launch the computation and return;
the judge synchronizes after the call.

## Example

| A | B | C |
| --- | --- | --- |
| 1 2 3 4 | 5 6 7 8 | 6 8 10 12 |

## Constraints

- 1 ≤ N ≤ 10⁸;
- the answer is compared with a tolerance of `1e-6` (absolute and relative).
