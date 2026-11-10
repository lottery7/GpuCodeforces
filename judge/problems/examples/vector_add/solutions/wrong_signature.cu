#include <cuda_runtime.h>

__global__ void vector_add(const float* A, const float* B, float* C, long N) {
    long i = blockIdx.x * (long)blockDim.x + threadIdx.x;
    if (i < N) C[i] = A[i] + B[i];
}

// `long N` instead of `int N`: link error -> COMPILATION_ERROR with a clear message.
void solve(const float* A, const float* B, float* C, long N) {
    vector_add<<<(N + 255) / 256, 256>>>(A, B, C, N);
}
