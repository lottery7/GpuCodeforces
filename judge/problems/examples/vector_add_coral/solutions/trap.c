// An instruction the core does not have (a compressed one would do too): it traps, a RUNTIME_ERROR.
#include <stdint.h>

void solve(const int32_t* A, const int32_t* B, int32_t* C, int32_t N) {
    for (int32_t i = 0; i < N; ++i) C[i] = (int32_t)((uint32_t)A[i] + (uint32_t)B[i]);
    __asm__ volatile(".word 0xffffffff");
}
