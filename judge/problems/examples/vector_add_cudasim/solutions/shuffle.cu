// Uses a warp shuffle, which GPGPU-Sim computes wrongly: rejected before it runs (POLICY_VIOLATION).
#include <cuda_runtime.h>

__global__ void add(const float* A, const float* B, float* C, int N) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    float a = i < N ? A[i] : 0.0f;
    float same = __shfl_sync(0xffffffffu, a, threadIdx.x % 32);
    if (i < N) C[i] = same + B[i];
}

void solve(const float* A, const float* B, float* C, int N) {
    add<<<(N + 255) / 256, 256>>>(A, B, C, N);
}
