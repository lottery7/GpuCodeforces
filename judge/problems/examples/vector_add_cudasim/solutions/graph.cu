// Launches the kernel through a CUDA graph, which GPGPU-Sim's runtime does not provide: COMPILATION_ERROR.
#include <cuda_runtime.h>

__global__ void add(const float* A, const float* B, float* C, int N) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i < N) C[i] = A[i] + B[i];
}

void solve(const float* A, const float* B, float* C, int N) {
    cudaStream_t s;
    cudaStreamCreate(&s);
    cudaGraph_t g;
    cudaGraphExec_t e;
    cudaStreamBeginCapture(s, cudaStreamCaptureModeGlobal);
    add<<<(N + 255) / 256, 256, 0, s>>>(A, B, C, N);
    cudaStreamEndCapture(s, &g);
    cudaGraphInstantiate(&e, g, 0);
    cudaGraphLaunch(e, s);
    cudaStreamSynchronize(s);
}
