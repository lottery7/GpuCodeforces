#include <cuda_runtime.h>

__global__ void add(const float* A, const float* B, float* C, int N) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i < N) C[i] = A[i] + B[i];
}

void solve(const float* A, const float* B, float* C, int N) {
    add<<<(N + 255) / 256, 256>>>(A, B, C, N);
}
