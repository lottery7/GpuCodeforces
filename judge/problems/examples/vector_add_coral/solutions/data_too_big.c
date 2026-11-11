// 16 KB of its own data: with the 24 KB of the largest test's arrays that is more than the data memory, a
// MEMORY_LIMIT_EXCEEDED on that test. (A static array the compiler can see through would be optimized away.)
#include <stdint.h>

int32_t scratch[4096];

void solve(const int32_t* A, const int32_t* B, int32_t* C, int32_t N) {
    for (int32_t i = 0; i < N; ++i) {
        scratch[i] = (int32_t)((uint32_t)A[i] + (uint32_t)B[i]);
        C[i] = scratch[i];
    }
}
