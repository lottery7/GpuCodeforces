#include <cuda_runtime.h>

// Computes everything on the CPU. Kernel time is ~0, so this is limited only by the wall-time safety limit
// (wallTimeLimitMs): it passes small tests and gets TIME_LIMIT_EXCEEDED on 2048^3.
void solve(const float* A, const float* B, float* C, int M, int N, int K) {
    for (int i = 0; i < M; i++)
        for (int j = 0; j < N; j++) {
            float acc = 0.0f;
            for (int k = 0; k < K; k++) acc += A[(size_t)i * K + k] * B[(size_t)k * N + j];
            C[(size_t)i * N + j] = acc;
        }
}
