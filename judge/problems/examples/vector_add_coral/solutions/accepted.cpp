// C++ without its standard library (the target has none): solve keeps C++ linkage, the judge compiles its harness
// as C++ too. The constructor of a global runs before solve.
#include <stdint.h>

namespace {
struct Adder {
    uint32_t bias;
    Adder() : bias(0) {}
    template <typename T> T operator()(T a, T b) const { return static_cast<T>(static_cast<uint32_t>(a) + static_cast<uint32_t>(b) + bias); }
};
Adder add;
}

void solve(const int32_t* A, const int32_t* B, int32_t* C, int32_t N) {
    for (int32_t i = 0; i < N; ++i) C[i] = add(A[i], B[i]);
}
