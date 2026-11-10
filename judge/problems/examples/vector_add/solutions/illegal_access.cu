#include <cuda_runtime.h>

// Writes far out of bounds -> cudaErrorIllegalAddress -> RUNTIME_ERROR.
__global__ void bad(float* C, int N) {
    C[(size_t)threadIdx.x * 1000000000ull] = 1.0f;
}

void solve(const float* A, const float* B, float* C, int N) {
    bad<<<1, 32>>>(C, N);
}
