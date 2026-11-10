#include <cuda_runtime.h>

// Infinite loop on the GPU: the watchdog must kill the process (TIME_LIMIT_EXCEEDED) and the GPU must recover.
__global__ void spin(volatile int* flag) {
    while (*flag == 0) {
    }
}

void solve(const float* A, const float* B, float* C, int N) {
    int* flag = nullptr;
    cudaMalloc(&flag, sizeof(int));
    cudaMemset(flag, 0, sizeof(int));
    spin<<<1, 1>>>(flag);
}
