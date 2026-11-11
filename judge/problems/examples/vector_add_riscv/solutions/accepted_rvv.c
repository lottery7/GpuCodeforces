// The vector extension: up to 64 elements per instruction with LMUL = 8 at VLEN = 256.
#include <riscv_vector.h>
#include <stdint.h>

void solve(const int32_t* A, const int32_t* B, int32_t* C, int32_t N) {
    for (size_t i = 0, n = (size_t)N; i < n;) {
        size_t vl = __riscv_vsetvl_e32m8(n - i);
        vint32m8_t a = __riscv_vle32_v_i32m8(A + i, vl);
        vint32m8_t b = __riscv_vle32_v_i32m8(B + i, vl);
        __riscv_vse32_v_i32m8(C + i, __riscv_vadd_vv_i32m8(a, b, vl), vl);
        i += vl;
    }
}
