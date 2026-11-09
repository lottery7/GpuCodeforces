#include <cuda_runtime.h>

// Off-by-one: the last element is never written (stays NaN-poisoned) -> WRONG_ANSWER.
__global__ void vector_add(const float* A, const float* B, float* C, int N) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i < N - 1) C[i] = A[i] + B[i];
}

void solve(const float* A, const float* B, float* C, int N) {
    vector_add<<<(N + 255) / 256, 256>>>(A, B, C, N);
}
