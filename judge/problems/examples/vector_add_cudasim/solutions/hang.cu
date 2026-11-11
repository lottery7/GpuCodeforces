// A kernel that never ends: TIME_LIMIT_EXCEEDED once the cycles the limit allows run out.
#include <cuda_runtime.h>

__global__ void spin(float* C, int N) {
    volatile int forever = 1;
    while (forever) C[0] = (float)N;
}

void solve(const float* A, const float* B, float* C, int N) {
    spin<<<1, 32>>>(C, N);
}
