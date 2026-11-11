// Does not compile: COMPILATION_ERROR with nvcc's message.
#include <cuda_runtime.h>

void solve(const float* A, const float* B, float* C, int N) {
    add<<<1, 1>>>(A, B, C, N)
}
