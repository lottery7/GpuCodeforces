// Formatting a double pulls more of the C library than the 8 KB of instruction memory hold: the link fails, a
// COMPILATION_ERROR.
#include <stdint.h>
#include <stdio.h>

void solve(const int32_t* A, const int32_t* B, int32_t* C, int32_t N) {
    char text[64];
    snprintf(text, sizeof text, "%f %d", (double)A[0] + (double)B[0], (int)N);
    C[0] = text[0];
}
