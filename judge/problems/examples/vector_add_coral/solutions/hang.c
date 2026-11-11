// Never returns: TIME_LIMIT_EXCEEDED once the instructions the limit allows run out.
#include <stdint.h>

void solve(const int32_t* A, const int32_t* B, int32_t* C, int32_t N) {
    volatile int32_t spin = 1;
    while (spin) C[0] = A[0] + B[0] + N;
}
