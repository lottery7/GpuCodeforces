// Writes far outside C: the guest gets SIGSEGV, a RUNTIME_ERROR.
#include <stdint.h>

void solve(const int32_t* A, const int32_t* B, int32_t* C, int32_t N) {
    (void)A; (void)B;
    for (int64_t i = 0; i < (int64_t)N << 30; i += 1 << 20) C[i] = 0;
}
