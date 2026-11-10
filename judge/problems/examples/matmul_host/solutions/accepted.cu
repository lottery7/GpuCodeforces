#include <cuda_runtime.h>

// Data starts on the host: the solution moves it to the GPU itself. In this problem only GPU kernel time counts
// (timing mode GPU_KERNELS), copies are free but bounded by the wall-time safety limit.
constexpr int TILE = 16;

__global__ void matmul(const float* A, const float* B, float* C, int M, int N, int K) {
    __shared__ float As[TILE][TILE];
    __shared__ float Bs[TILE][TILE];
    int row = blockIdx.y * TILE + threadIdx.y;
    int col = blockIdx.x * TILE + threadIdx.x;
    float acc = 0.0f;
    for (int t = 0; t < (K + TILE - 1) / TILE; t++) {
        int ak = t * TILE + threadIdx.x;
        int bk = t * TILE + threadIdx.y;
        As[threadIdx.y][threadIdx.x] = (row < M && ak < K) ? A[(size_t)row * K + ak] : 0.0f;
        Bs[threadIdx.y][threadIdx.x] = (bk < K && col < N) ? B[(size_t)bk * N + col] : 0.0f;
        __syncthreads();
        for (int i = 0; i < TILE; i++) acc += As[threadIdx.y][i] * Bs[i][threadIdx.x];
        __syncthreads();
    }
    if (row < M && col < N) C[(size_t)row * N + col] = acc;
}

// A, B, C are host pointers
void solve(const float* A, const float* B, float* C, int M, int N, int K) {
    float *dA, *dB, *dC;
    cudaMalloc(&dA, sizeof(float) * M * K);
    cudaMalloc(&dB, sizeof(float) * K * N);
    cudaMalloc(&dC, sizeof(float) * M * N);
    cudaMemcpy(dA, A, sizeof(float) * M * K, cudaMemcpyHostToDevice);
    cudaMemcpy(dB, B, sizeof(float) * K * N, cudaMemcpyHostToDevice);
    dim3 block(TILE, TILE);
    dim3 grid((N + TILE - 1) / TILE, (M + TILE - 1) / TILE);
    matmul<<<grid, block>>>(dA, dB, dC, M, N, K);
    cudaMemcpy(C, dC, sizeof(float) * M * N, cudaMemcpyDeviceToHost);
    cudaFree(dA);
    cudaFree(dB);
    cudaFree(dC);
}
