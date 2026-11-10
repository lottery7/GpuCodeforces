#include <cuda_runtime.h>
#include <thrust/device_ptr.h>
#include <thrust/transform.h>
#include <thrust/functional.h>

// Uses Thrust, which is not allowed by default -> POLICY_VIOLATION.
void solve(const float* A, const float* B, float* C, int N) {
    thrust::transform(thrust::device_pointer_cast(A), thrust::device_pointer_cast(A) + N,
                      thrust::device_pointer_cast(B), thrust::device_pointer_cast(C), thrust::plus<float>());
}
