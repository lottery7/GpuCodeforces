// Does not compile: COMPILATION_ERROR with the compiler's message.
#include <stdint.h>

void solve(const int32_t* A, const int32_t* B, int32_t* C, int32_t N) {
    for (int32_t i = 0; i < N; ++i) C[i] = A[i] + B[i]
}
