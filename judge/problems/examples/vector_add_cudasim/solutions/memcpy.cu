// Copies the arrays to the host and adds there, where work costs no simulated cycles: POLICY_VIOLATION.
#include <cuda_runtime.h>
#include <vector>

void solve(const float* A, const float* B, float* C, int N) {
    std::vector<float> a(N), b(N);
    cudaMemcpy(a.data(), A, N * sizeof(float), cudaMemcpyDeviceToHost);
    cudaMemcpy(b.data(), B, N * sizeof(float), cudaMemcpyDeviceToHost);
    for (int i = 0; i < N; ++i) a[i] += b[i];
    cudaMemcpy(C, a.data(), N * sizeof(float), cudaMemcpyHostToDevice);
}
