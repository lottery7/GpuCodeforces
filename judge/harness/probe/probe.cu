// GPU health probe: run by the executor after a solution had to be killed and periodically while idle.
// Exit code 0 and "ok" on stdout means the GPU can create a context, run a kernel and copy data back.
#include <cuda_runtime.h>

#include <cstdio>
#include <vector>

__global__ void probe_kernel(float* x, int n) {
    int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i < n) x[i] = x[i] * 2.0f + 1.0f;
}

int main() {
    const int n = 1 << 20;
    std::vector<float> h(n);
    for (int i = 0; i < n; i++) h[i] = (float)(i % 1000);
    float* d = nullptr;
    cudaError_t e = cudaMalloc(&d, n * sizeof(float));
    if (e == cudaSuccess) e = cudaMemcpy(d, h.data(), n * sizeof(float), cudaMemcpyHostToDevice);
    if (e == cudaSuccess) {
        probe_kernel<<<(n + 255) / 256, 256>>>(d, n);
        e = cudaDeviceSynchronize();
    }
    if (e == cudaSuccess) e = cudaMemcpy(h.data(), d, n * sizeof(float), cudaMemcpyDeviceToHost);
    if (e != cudaSuccess) {
        printf("error %s\n", cudaGetErrorString(e));
        return 1;
    }
    for (int i = 0; i < n; i++) {
        if (h[i] != (float)(i % 1000) * 2.0f + 1.0f) {
            printf("error wrong result at %d\n", i);
            return 2;
        }
    }
    cudaDeviceProp prop{};
    cudaGetDeviceProperties(&prop, 0);
    printf("ok %s sm_%d%d\n", prop.name, prop.major, prop.minor);
    cudaFree(d);
    return 0;
}
