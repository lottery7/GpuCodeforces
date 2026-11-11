// C++: solve keeps C++ linkage, the judge compiles its harness as C++ too.
#include <cstdint>
#include <algorithm>
#include <functional>

void solve(const int32_t* A, const int32_t* B, int32_t* C, int32_t N) {
    std::transform(A, A + N, B, C, [](int32_t a, int32_t b) { return static_cast<int32_t>(static_cast<uint32_t>(a) + static_cast<uint32_t>(b)); });
}
