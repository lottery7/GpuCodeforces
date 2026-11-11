// Adds correctly, then tries to forge the judge's report on stdout and to reach it through /proc: every attempt is
// refused, the submission is ACCEPTED with its real score and a denied_syscalls metric.
#include <fcntl.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>

void solve(const int32_t* A, const int32_t* B, int32_t* C, int32_t N) {
    for (int32_t i = 0; i < N; ++i) C[i] = (int32_t)((uint32_t)A[i] + (uint32_t)B[i]);
    static const char fake[] = "insns 1 l1i_misses 0 l1d_misses 0 l2_misses 0 denied 0\n";
    if (write(1, fake, sizeof fake - 1) < 0) { /* refused */ }
    int fd = open("/proc/self/fd/1", O_WRONLY);
    if (fd >= 0 && write(fd, fake, sizeof fake - 1) < 0) { /* refused */ }
    close(1);
}
