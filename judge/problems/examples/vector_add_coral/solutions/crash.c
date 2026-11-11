// Writes past the data memory (0x10000..0x17FFF): the core raises FAULT, a RUNTIME_ERROR.
#include <stdint.h>

void solve(const int32_t* A, const int32_t* B, int32_t* C, int32_t N) {
    (void)A; (void)B; (void)C; (void)N;
    *(volatile int32_t*)0x20000 = 1;
}
