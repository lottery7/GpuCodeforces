// The riscv-rvv judge plugin for QEMU user mode (QEMU 11, plugin API 7). Built into executor-emu.Dockerfile.
//
// Counts guest instructions and simulates L1I and L1D (32 KiB, 8 ways) and a unified L2 (1 MiB, 16 ways), 64-byte
// lines, LRU, on every instruction fetch and memory access. At exit writes
//   <nonce> insns <n> l1i_misses <n> l1d_misses <n> l2_misses <n> denied <n>
// to the file descriptor `fd` (stdout for the judge); past `limit` instructions it writes "<nonce> limit" and stops
// QEMU with exit code 4.
//
// The guest shares the process, its file descriptors and its file system with QEMU, so the syscall filter keeps it
// away from that descriptor and from everything that could reach it again: /proc, other processes, threads, exec,
// files outside the allowed directories. Denied calls fail with EPERM and are counted. What the filter cannot do is
// keep the guest out of QEMU's memory, where the counters live: see judge/README.md.
//
// Arguments: fd=<n>,nonce=<hex>,limit=<instructions>,allow=<dir>[,allow=<dir>...]
#include <errno.h>
#include <fcntl.h>
#include <glib.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <qemu-plugin.h>

QEMU_PLUGIN_EXPORT int qemu_plugin_version = QEMU_PLUGIN_VERSION;

typedef struct {
    uint64_t *tags; /* (tag << 1) | valid, per set and way */
    uint64_t *stamps;
    int sets, ways, block_bits;
    uint64_t clock, misses;
} Cache;

static Cache l1i, l1d, l2;
static uint64_t insns;
static int channel = -1;
static uint64_t limit = UINT64_MAX;
static char nonce[64];
static char *allowed[8];
static int n_allowed;
static int violations;

static void cache_init(Cache *c, int size, int ways, int block) {
    c->ways = ways;
    c->block_bits = __builtin_ctz(block);
    c->sets = size / (ways * block);
    c->tags = calloc((size_t)c->sets * ways, sizeof(uint64_t));
    c->stamps = calloc((size_t)c->sets * ways, sizeof(uint64_t));
}

/* 1 on a miss; the block is then loaded in place of the least recently used one */
static int cache_access(Cache *c, uint64_t addr) {
    uint64_t block = addr >> c->block_bits;
    int set = (int)(block % (uint64_t)c->sets);
    uint64_t tag = ((block / (uint64_t)c->sets) << 1) | 1;
    uint64_t *tags = c->tags + (size_t)set * c->ways, *stamps = c->stamps + (size_t)set * c->ways;
    int victim = 0;
    c->clock++;
    for (int w = 0; w < c->ways; ++w) {
        if (tags[w] == tag) { stamps[w] = c->clock; return 0; }
        if (stamps[w] < stamps[victim]) victim = w;
    }
    tags[victim] = tag;
    stamps[victim] = c->clock;
    c->misses++;
    return 1;
}

static void on_insn(unsigned int vcpu, void *udata) {
    if (++insns > limit) {
        char line[96];
        int n = snprintf(line, sizeof line, "%s limit\n", nonce);
        if (write(channel, line, (size_t)n) != n) _exit(125);
        _exit(4);
    }
    if (cache_access(&l1i, (uint64_t)(uintptr_t)udata)) cache_access(&l2, (uint64_t)(uintptr_t)udata);
}

static void on_mem(unsigned int vcpu, qemu_plugin_meminfo_t info, uint64_t vaddr, void *udata) {
    if (cache_access(&l1d, vaddr)) cache_access(&l2, vaddr);
}

static void on_tb(struct qemu_plugin_tb *tb, void *udata) {
    for (size_t i = 0; i < qemu_plugin_tb_n_insns(tb); ++i) {
        struct qemu_plugin_insn *insn = qemu_plugin_tb_get_insn(tb, i);
        uint64_t vaddr = qemu_plugin_insn_vaddr(insn);
        qemu_plugin_register_vcpu_insn_exec_cb(insn, on_insn, QEMU_PLUGIN_CB_NO_REGS, (void *)(uintptr_t)vaddr);
        qemu_plugin_register_vcpu_mem_cb(insn, on_mem, QEMU_PLUGIN_CB_NO_REGS, QEMU_PLUGIN_MEM_RW, NULL);
    }
}

/* the guest's NUL-terminated string at vaddr, or NULL */
static char *guest_string(uint64_t vaddr) {
    GByteArray *buf = g_byte_array_new();
    char *s = NULL;
    for (size_t i = 0; i < PATH_MAX; ++i) {
        g_byte_array_set_size(buf, 0);
        if (!qemu_plugin_read_memory_vaddr(vaddr + i, buf, 1)) break;
        if (buf->data[0] == 0) {
            s = g_malloc(i + 1);
            for (size_t j = 0; j < i; ++j) {
                g_byte_array_set_size(buf, 0);
                qemu_plugin_read_memory_vaddr(vaddr + j, buf, 1);
                s[j] = (char)buf->data[0];
            }
            s[i] = 0;
            break;
        }
    }
    g_byte_array_free(buf, TRUE);
    return s;
}

/* An open may only name a file below an allowed directory, after symlinks: never /proc/self/fd/<channel>. */
static int open_allowed(int64_t dirfd, uint64_t path_vaddr, int flags) {
    if (dirfd != AT_FDCWD) return 0;
    char *path = guest_string(path_vaddr);
    if (!path) return 0;
    char resolved[PATH_MAX];
    int ok = 0;
    /* a file that does not exist yet (an output) is checked by its directory */
    char *dir = g_path_get_dirname(path);
    if (realpath(path, resolved) || ((flags & O_CREAT) && realpath(dir, resolved))) {
        for (int i = 0; i < n_allowed; ++i) {
            size_t n = strlen(allowed[i]);
            if (strncmp(resolved, allowed[i], n) == 0 && (resolved[n] == '/' || resolved[n] == 0)) ok = 1;
        }
    }
    g_free(dir);
    g_free(path);
    return ok;
}

/* riscv64 uses the generic syscall table */
enum {
    SYS_dup = 23, SYS_dup3 = 24, SYS_fcntl = 25, SYS_ioctl = 29, SYS_flock = 32, SYS_mknodat = 33,
    SYS_mkdirat = 34, SYS_unlinkat = 35, SYS_symlinkat = 36, SYS_linkat = 37, SYS_renameat = 38,
    SYS_ftruncate = 46, SYS_fallocate = 47, SYS_fchdir = 50, SYS_openat = 56, SYS_close = 57, SYS_lseek = 62,
    SYS_read = 63, SYS_write = 64, SYS_readv = 65, SYS_writev = 66, SYS_pread64 = 67, SYS_pwrite64 = 68,
    SYS_preadv = 69, SYS_pwritev = 70, SYS_sendfile = 71, SYS_vmsplice = 75, SYS_splice = 76, SYS_tee = 77,
    SYS_fsync = 82, SYS_fdatasync = 83, SYS_sync_file_range = 84, SYS_clone = 220, SYS_execve = 221,
    SYS_mmap = 222, SYS_renameat2 = 276, SYS_execveat = 281, SYS_copy_file_range = 285, SYS_preadv2 = 286,
    SYS_pwritev2 = 287, SYS_clone3 = 435, SYS_close_range = 436, SYS_openat2 = 437,
};

static bool on_syscall(unsigned int vcpu, int64_t num, uint64_t a1, uint64_t a2, uint64_t a3, uint64_t a4,
                       uint64_t a5, uint64_t a6, uint64_t a7, uint64_t a8, int64_t *ret, void *udata) {
    /* fds are C ints: the kernel and QEMU look at the low 32 bits only, and RV64 sign-extends them */
    int32_t k = channel;
    bool deny;
    switch (num) {
    case SYS_dup: case SYS_fcntl: case SYS_ioctl: case SYS_flock: case SYS_ftruncate: case SYS_fallocate:
    case SYS_close: case SYS_lseek: case SYS_read: case SYS_write: case SYS_readv: case SYS_writev:
    case SYS_pread64: case SYS_pwrite64: case SYS_preadv: case SYS_pwritev: case SYS_vmsplice: case SYS_fsync:
    case SYS_fdatasync: case SYS_sync_file_range: case SYS_preadv2: case SYS_pwritev2: case SYS_fchdir:
        deny = (int32_t)a1 == k;
        break;
    case SYS_dup3: deny = (int32_t)a1 == k || (int32_t)a2 == k; break;
    case SYS_sendfile: case SYS_tee: deny = (int32_t)a1 == k || (int32_t)a2 == k; break;
    case SYS_splice: case SYS_copy_file_range: deny = (int32_t)a1 == k || (int32_t)a3 == k; break;
    case SYS_mmap: deny = (int32_t)a5 == k; break;
    case SYS_close_range: deny = (uint32_t)a1 <= (uint32_t)k && (uint32_t)k <= (uint32_t)a2; break;
    case SYS_openat: deny = !open_allowed((int32_t)a1, a2, (int)a3); break;
    /* nothing a solution needs, and each is a way around the checks above or around the counting */
    case SYS_clone: case SYS_clone3: case SYS_execve: case SYS_execveat: case SYS_openat2: case SYS_mknodat:
    case SYS_mkdirat: case SYS_unlinkat: case SYS_symlinkat: case SYS_linkat: case SYS_renameat: case SYS_renameat2:
        deny = true;
        break;
    default:
        deny = false;
    }
    if (deny) {
        violations++;
        *ret = -EPERM;
    }
    return deny;
}

static void on_atexit(void *udata) {
    char line[256];
    int n = snprintf(line, sizeof line, "%s insns %llu l1i_misses %llu l1d_misses %llu l2_misses %llu denied %d\n", nonce,
                     (unsigned long long)insns, (unsigned long long)l1i.misses, (unsigned long long)l1d.misses,
                     (unsigned long long)l2.misses, violations);
    if (write(channel, line, (size_t)n) != n) _exit(125);
}

QEMU_PLUGIN_EXPORT int qemu_plugin_install(qemu_plugin_id_t id, const qemu_info_t *info, int argc, char **argv) {
    for (int i = 0; i < argc; ++i) {
        char *eq = strchr(argv[i], '=');
        if (!eq) return -1;
        *eq = 0;
        const char *v = eq + 1;
        if (!strcmp(argv[i], "fd")) channel = atoi(v);
        else if (!strcmp(argv[i], "nonce")) g_strlcpy(nonce, v, sizeof nonce);
        else if (!strcmp(argv[i], "limit")) limit = strtoull(v, NULL, 10);
        else if (!strcmp(argv[i], "allow") && n_allowed < 8) allowed[n_allowed++] = realpath(v, NULL);
        else return -1;
    }
    if (channel < 0 || !nonce[0]) return -1;
    cache_init(&l1i, 32 * 1024, 8, 64);
    cache_init(&l1d, 32 * 1024, 8, 64);
    cache_init(&l2, 1024 * 1024, 16, 64);
    qemu_plugin_register_vcpu_tb_trans_cb(id, on_tb, NULL);
    qemu_plugin_register_vcpu_syscall_filter_cb(id, on_syscall, NULL);
    qemu_plugin_register_atexit_cb(id, on_atexit, NULL);
    return 0;
}
