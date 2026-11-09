// GPU Codeforces judge harness runtime.
//
// Included exactly once, by the harness main generated for each problem signature (see HarnessGenerator.kt).
// The harness is linked with the contestant's object file and runs inside the sandbox:
//
//   ./solution.bin manifest.txt
//
// Protocol (all lines on the ORIGINAL stdout, prefixed with a per-run nonce; the contestant's stdout is
// redirected to stderr so it cannot be confused with status lines):
//   ready                                      CUDA context is up
//   load <test> <run>                          loading inputs (untimed)
//   run <test> <run>                           calling solve()
//   test <test> ok runs <n> time_ns <t> wall_ns <w>
//   test <test> runtime_error <message>
//   test <test> alloc_failed <message>         harness could not allocate test buffers (memory exhausted)
//   fatal <message>                            harness problem, not caused by the solution
//   done
// Outputs of a test are written to the paths from the manifest; the executor compares them with the
// reference outside the sandbox. Expected answers are never visible to the solution process.
#pragma once

#include <cuda_runtime.h>

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include <fcntl.h>
#include <unistd.h>

namespace judge {

enum class Role { Input, Output, InOut, Scalar };
enum class Mem { None, Device, Host };

struct ParamDesc {
    const char* name;
    int elemBytes;
    Role role;
    Mem mem;
    char scalarKind;  // 'i' signed, 'u' unsigned, 'f' floating, 0 for arrays
};

union Scalar {
    long long i;
    unsigned long long u;
    double d;
};

using InvokeFn = void (*)(void* const* ptrs, const Scalar* scalars);

struct ArraySpec {
    int param = -1;
    size_t bytes = 0;
    std::string in;   // "-" if none
    std::string out;  // "-" if none
};

struct TestSpec {
    int index = 0;
    std::vector<ArraySpec> arrays;
    std::vector<std::pair<int, std::string>> scalars;
};

struct Config {
    std::string nonce;
    bool kernelTiming = false;
    int warmup = 0;
    int maxRuns = 1;
    long long timeLimitNs = 0;
    long long wallLimitNs = 0;
    double greyFactor = 1.25;
    std::vector<TestSpec> tests;
};

// ------------------------------------------------------------------------------------------------
// Status channel
// ------------------------------------------------------------------------------------------------

static int g_status_fd = -1;
static std::string g_nonce = "-";

static void status(const char* fmt, ...) {
    char buf[4096];
    int n = snprintf(buf, sizeof buf, "%s ", g_nonce.c_str());
    va_list ap;
    va_start(ap, fmt);
    int m = vsnprintf(buf + n, sizeof buf - n - 2, fmt, ap);
    va_end(ap);
    n += std::min(m, (int)(sizeof buf - n - 2));
    for (int i = 0; i < n; i++) {
        if (buf[i] == '\n' || buf[i] == '\r') buf[i] = ' ';
    }
    buf[n++] = '\n';
    int off = 0;
    while (off < n) {
        ssize_t w = write(g_status_fd >= 0 ? g_status_fd : 1, buf + off, n - off);
        if (w < 0 && errno == EINTR) continue;
        if (w <= 0) _exit(120);
        off += (int)w;
    }
}

[[noreturn]] static void fatal(const char* what, const std::string& detail) {
    status("fatal %s: %s", what, detail.c_str());
    _exit(121);
}

// ------------------------------------------------------------------------------------------------
// Manifest
// ------------------------------------------------------------------------------------------------

static std::vector<std::string> split_ws(const std::string& line) {
    std::vector<std::string> out;
    size_t i = 0;
    while (i < line.size()) {
        while (i < line.size() && (line[i] == ' ' || line[i] == '\t')) i++;
        size_t j = i;
        while (j < line.size() && line[j] != ' ' && line[j] != '\t') j++;
        if (j > i) out.emplace_back(line.substr(i, j - i));
        i = j;
    }
    return out;
}

static Config parse_manifest(const char* path, int nparams) {
    FILE* f = fopen(path, "r");
    if (!f) fatal("manifest", std::string(path) + ": " + strerror(errno));
    Config cfg;
    TestSpec* cur = nullptr;
    char* line = nullptr;
    size_t cap = 0;
    ssize_t len;
    while ((len = getline(&line, &cap, f)) > 0) {
        std::string s(line, (size_t)len);
        while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
        auto t = split_ws(s);
        if (t.empty()) continue;
        const std::string& k = t[0];
        auto need = [&](size_t n) {
            if (t.size() != n) fatal("manifest", "bad line: " + s);
        };
        if (k == "nonce") { need(2); cfg.nonce = t[1]; }
        else if (k == "timing") { need(2); cfg.kernelTiming = (t[1] == "kernels"); }
        else if (k == "warmup") { need(2); cfg.warmup = atoi(t[1].c_str()); }
        else if (k == "max_runs") { need(2); cfg.maxRuns = std::max(1, atoi(t[1].c_str())); }
        else if (k == "time_limit_ns") { need(2); cfg.timeLimitNs = atoll(t[1].c_str()); }
        else if (k == "wall_limit_ns") { need(2); cfg.wallLimitNs = atoll(t[1].c_str()); }
        else if (k == "grey_factor") { need(2); cfg.greyFactor = atof(t[1].c_str()); }
        else if (k == "params") {
            need(2);
            if (atoi(t[1].c_str()) != nparams) fatal("manifest", "param count mismatch");
        } else if (k == "test") {
            need(2);
            cfg.tests.emplace_back();
            cur = &cfg.tests.back();
            cur->index = atoi(t[1].c_str());
        } else if (k == "array") {
            need(5);
            if (!cur) fatal("manifest", "array outside test");
            ArraySpec a;
            a.param = atoi(t[1].c_str());
            a.bytes = (size_t)strtoull(t[2].c_str(), nullptr, 10);
            a.in = t[3];
            a.out = t[4];
            if (a.param < 0 || a.param >= nparams) fatal("manifest", "bad param index: " + s);
            cur->arrays.push_back(a);
        } else if (k == "scalar") {
            need(3);
            if (!cur) fatal("manifest", "scalar outside test");
            int p = atoi(t[1].c_str());
            if (p < 0 || p >= nparams) fatal("manifest", "bad param index: " + s);
            cur->scalars.emplace_back(p, t[2]);
        } else if (k == "end") {
            cur = nullptr;
        } else {
            fatal("manifest", "unknown key: " + k);
        }
    }
    free(line);
    fclose(f);
    if (cfg.nonce.empty()) fatal("manifest", "missing nonce");
    return cfg;
}

// ------------------------------------------------------------------------------------------------
// Kernel timing (GPU_KERNELS mode).
// The final link wraps the CUDA runtime launch entry points (-Xlinker --wrap=...). While a solve() call is
// being measured every launch is bracketed with events on its stream; the measured time is the length of the
// union of [start, stop] intervals, i.e. the time the GPU was busy with the solution's kernels.
// ------------------------------------------------------------------------------------------------

struct LaunchRec {
    cudaEvent_t start;
    cudaEvent_t stop;
};

static std::mutex g_launch_mu;
static bool g_tracking = false;
static std::vector<LaunchRec> g_pool;
static size_t g_used = 0;
static cudaEvent_t g_base;

static void ensure_pool(size_t n) {
    while (g_pool.size() < n) {
        LaunchRec r;
        if (cudaEventCreate(&r.start) != cudaSuccess || cudaEventCreate(&r.stop) != cudaSuccess) return;
        g_pool.push_back(r);
    }
}

static LaunchRec* begin_launch(cudaStream_t stream) {
    std::lock_guard<std::mutex> lock(g_launch_mu);
    if (!g_tracking) return nullptr;
    if (g_used == g_pool.size()) ensure_pool(g_pool.size() * 2 + 64);
    if (g_used == g_pool.size()) return nullptr;
    LaunchRec* r = &g_pool[g_used++];
    cudaEventRecord(r->start, stream);
    return r;
}

static void end_launch(LaunchRec* r, cudaStream_t stream) {
    if (r) cudaEventRecord(r->stop, stream);
}

static long long kernel_union_ns(size_t n) {
    std::vector<std::pair<float, float>> iv;
    iv.reserve(n);
    for (size_t i = 0; i < n; i++) {
        float a = 0, b = 0;
        if (cudaEventElapsedTime(&a, g_base, g_pool[i].start) != cudaSuccess) continue;
        if (cudaEventElapsedTime(&b, g_base, g_pool[i].stop) != cudaSuccess) continue;
        if (b > a) iv.emplace_back(a, b);
    }
    std::sort(iv.begin(), iv.end());
    double total = 0, cs = 0, ce = -1;
    bool open = false;
    for (auto& p : iv) {
        if (!open || p.first > ce) {
            if (open) total += ce - cs;
            cs = p.first;
            ce = p.second;
            open = true;
        } else {
            ce = std::max(ce, (double)p.second);
        }
    }
    if (open) total += ce - cs;
    cudaGetLastError();  // elapsed-time failures must not leak into the solution's error state
    return (long long)(total * 1e6);
}

// ------------------------------------------------------------------------------------------------
// Buffers
// ------------------------------------------------------------------------------------------------

struct Buffers {
    std::vector<void*> ptrs;
    std::vector<Mem> mem;
};

static std::vector<char> g_staging;
static void* g_l2_scratch = nullptr;
static size_t g_l2_bytes = 0;

static void free_buffers(Buffers& b) {
    for (size_t i = 0; i < b.ptrs.size(); i++) {
        if (!b.ptrs[i]) continue;
        if (b.mem[i] == Mem::Device) cudaFree(b.ptrs[i]);
        else free(b.ptrs[i]);
        b.ptrs[i] = nullptr;
    }
}

static bool read_file(const std::string& path, void* dst, size_t bytes, std::string& err) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) { err = path + ": " + strerror(errno); return false; }
    size_t off = 0;
    while (off < bytes) {
        ssize_t r = read(fd, (char*)dst + off, std::min(bytes - off, (size_t)1 << 30));
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) { err = path + ": short read"; close(fd); return false; }
        off += (size_t)r;
    }
    close(fd);
    return true;
}

static bool write_file(const std::string& path, const void* src, size_t bytes, std::string& err) {
    int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) { err = path + ": " + strerror(errno); return false; }
    size_t off = 0;
    while (off < bytes) {
        ssize_t w = write(fd, (const char*)src + off, std::min(bytes - off, (size_t)1 << 30));
        if (w < 0 && errno == EINTR) continue;
        if (w <= 0) { err = path + ": write failed: " + strerror(errno); close(fd); return false; }
        off += (size_t)w;
    }
    close(fd);
    return true;
}

enum class Outcome { Ok, RuntimeError, AllocFailed };

// Allocates fresh buffers for one run, loads inputs and poisons outputs (0xFF bytes = NaN for floats).
static Outcome prepare(const ParamDesc* params, int nparams, const TestSpec& t, Buffers& b, std::string& err) {
    b.ptrs.assign(nparams, nullptr);
    b.mem.assign(nparams, Mem::None);
    for (const ArraySpec& a : t.arrays) {
        const ParamDesc& p = params[a.param];
        b.mem[a.param] = p.mem;
        if (a.bytes == 0) continue;
        if (p.mem == Mem::Device) {
            cudaError_t e = cudaMalloc(&b.ptrs[a.param], a.bytes);
            if (e != cudaSuccess) {
                b.ptrs[a.param] = nullptr;
                err = std::string("cudaMalloc(") + std::to_string(a.bytes) + ") for " + p.name + ": " + cudaGetErrorString(e);
                cudaGetLastError();
                return Outcome::AllocFailed;
            }
            if (a.in != "-") {
                if (g_staging.size() < a.bytes) g_staging.resize(a.bytes);
                if (!read_file(a.in, g_staging.data(), a.bytes, err)) fatal("input", err);
                e = cudaMemcpy(b.ptrs[a.param], g_staging.data(), a.bytes, cudaMemcpyHostToDevice);
            } else {
                e = cudaMemset(b.ptrs[a.param], 0xFF, a.bytes);
            }
            if (e != cudaSuccess) {
                err = std::string("loading ") + p.name + ": " + cudaGetErrorString(e);
                return Outcome::RuntimeError;
            }
        } else {
            void* ptr = nullptr;
            size_t rounded = (a.bytes + 4095) / 4096 * 4096;
            if (posix_memalign(&ptr, 4096, rounded) != 0) {
                err = std::string("host allocation of ") + std::to_string(a.bytes) + " bytes for " + p.name + " failed";
                return Outcome::AllocFailed;
            }
            b.ptrs[a.param] = ptr;
            if (a.in != "-") {
                if (!read_file(a.in, ptr, a.bytes, err)) fatal("input", err);
            } else {
                memset(ptr, 0xFF, a.bytes);
            }
        }
    }
    cudaError_t e = cudaDeviceSynchronize();
    if (e != cudaSuccess) {
        err = std::string("CUDA error before solve(): ") + cudaGetErrorString(e);
        return Outcome::RuntimeError;
    }
    return Outcome::Ok;
}

static bool save_outputs(const ParamDesc* params, const TestSpec& t, Buffers& b, std::string& err) {
    for (const ArraySpec& a : t.arrays) {
        if (a.out == "-") continue;
        const void* src = b.ptrs[a.param];
        if (a.bytes > 0 && params[a.param].mem == Mem::Device) {
            if (g_staging.size() < a.bytes) g_staging.resize(a.bytes);
            cudaError_t e = cudaMemcpy(g_staging.data(), b.ptrs[a.param], a.bytes, cudaMemcpyDeviceToHost);
            if (e != cudaSuccess) {
                err = std::string("reading output ") + params[a.param].name + ": " + cudaGetErrorString(e);
                return false;
            }
            src = g_staging.data();
        }
        if (!write_file(a.out, src, a.bytes, err)) fatal("output", err);
    }
    return true;
}

static void flush_l2() {
    if (g_l2_scratch) cudaMemset(g_l2_scratch, 0, g_l2_bytes);
}

struct Measurement {
    long long metricNs = 0;
    long long wallNs = 0;
    cudaError_t error = cudaSuccess;
};

static Measurement measure(bool kernelTiming, InvokeFn invoke, Buffers& b, const Scalar* scalars) {
    Measurement m;
    flush_l2();
    cudaDeviceSynchronize();
    cudaGetLastError();
    if (kernelTiming) {
        std::lock_guard<std::mutex> lock(g_launch_mu);
        g_used = 0;
        cudaEventRecord(g_base, 0);
        cudaEventSynchronize(g_base);
        g_tracking = true;
    }
    auto t0 = std::chrono::steady_clock::now();
    invoke(b.ptrs.data(), scalars);
    cudaError_t sync = cudaDeviceSynchronize();
    auto t1 = std::chrono::steady_clock::now();
    size_t launches = 0;
    if (kernelTiming) {
        std::lock_guard<std::mutex> lock(g_launch_mu);
        g_tracking = false;
        launches = g_used;
    }
    cudaError_t last = cudaGetLastError();
    m.error = sync != cudaSuccess ? sync : last;
    m.wallNs = std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count();
    m.metricNs = kernelTiming ? kernel_union_ns(launches) : m.wallNs;
    return m;
}

static std::vector<Scalar> parse_scalars(const ParamDesc* params, int nparams, const TestSpec& t) {
    std::vector<Scalar> s(nparams);
    for (auto& kv : t.scalars) {
        const ParamDesc& p = params[kv.first];
        if (p.scalarKind == 'f') s[kv.first].d = strtod(kv.second.c_str(), nullptr);
        else if (p.scalarKind == 'u') s[kv.first].u = strtoull(kv.second.c_str(), nullptr, 10);
        else s[kv.first].i = strtoll(kv.second.c_str(), nullptr, 10);
    }
    return s;
}

static long long median(std::vector<long long> v) {
    std::sort(v.begin(), v.end());
    size_t n = v.size();
    return n % 2 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2;
}

// ------------------------------------------------------------------------------------------------
// Entry point
// ------------------------------------------------------------------------------------------------

static int run_harness(int argc, char** argv, const ParamDesc* params, int nparams, InvokeFn invoke) {
    // Private status channel = original stdout. Everything the solution prints goes to stderr.
    g_status_fd = fcntl(1, F_DUPFD_CLOEXEC, 3);
    if (g_status_fd < 0) _exit(119);
    fflush(stdout);
    dup2(2, 1);

    if (argc < 2) fatal("usage", "solution.bin <manifest>");
    Config cfg = parse_manifest(argv[1], nparams);
    g_nonce = cfg.nonce;

    cudaError_t e = cudaFree(0);
    if (e != cudaSuccess) fatal("cuda_init", cudaGetErrorString(e));
    int dev = 0;
    cudaGetDevice(&dev);
    int l2 = 0;
    if (cudaDeviceGetAttribute(&l2, cudaDevAttrL2CacheSize, dev) == cudaSuccess && l2 > 0) {
        g_l2_bytes = (size_t)l2 * 2;
        if (cudaMalloc(&g_l2_scratch, g_l2_bytes) != cudaSuccess) {
            g_l2_scratch = nullptr;
            cudaGetLastError();
        }
    }
    if (cfg.kernelTiming) {
        if (cudaEventCreate(&g_base) != cudaSuccess) fatal("cuda_init", "cudaEventCreate failed");
        ensure_pool(256);
    }
    status("ready");

    std::string err;
    if (cfg.warmup > 0 && !cfg.tests.empty()) {
        const TestSpec& t = cfg.tests[0];
        std::vector<Scalar> scalars = parse_scalars(params, nparams, t);
        for (int w = 0; w < cfg.warmup; w++) {
            Buffers b;
            status("load %d warmup", t.index);
            Outcome o = prepare(params, nparams, t, b, err);
            if (o != Outcome::Ok) {
                status("test %d %s %s", t.index, o == Outcome::AllocFailed ? "alloc_failed" : "runtime_error", err.c_str());
                _exit(0);
            }
            status("run %d warmup", t.index);
            Measurement m = measure(cfg.kernelTiming, invoke, b, scalars.data());
            if (m.error != cudaSuccess) {
                status("test %d runtime_error %s (%s)", t.index, cudaGetErrorName(m.error), cudaGetErrorString(m.error));
                _exit(0);
            }
            free_buffers(b);
            if (m.wallNs > cfg.wallLimitNs) break;  // the first test will report the time limit properly
        }
    }

    for (const TestSpec& t : cfg.tests) {
        std::vector<Scalar> scalars = parse_scalars(params, nparams, t);
        std::vector<long long> metric, wall;
        for (int r = 0; r < cfg.maxRuns; r++) {
            Buffers b;
            status("load %d %d", t.index, r);
            Outcome o = prepare(params, nparams, t, b, err);
            if (o != Outcome::Ok) {
                status("test %d %s %s", t.index, o == Outcome::AllocFailed ? "alloc_failed" : "runtime_error", err.c_str());
                _exit(0);
            }
            status("run %d %d", t.index, r);
            Measurement m = measure(cfg.kernelTiming, invoke, b, scalars.data());
            if (m.error != cudaSuccess) {
                status("test %d runtime_error %s (%s)", t.index, cudaGetErrorName(m.error), cudaGetErrorString(m.error));
                _exit(0);
            }
            metric.push_back(m.metricNs);
            wall.push_back(m.wallNs);
            bool clear = m.metricNs <= cfg.timeLimitNs || (double)m.metricNs > cfg.timeLimitNs * cfg.greyFactor;
            bool last = r == cfg.maxRuns - 1 || (r == 0 && clear) || m.wallNs > cfg.wallLimitNs;
            if (last) {
                if (!save_outputs(params, t, b, err)) {
                    status("test %d runtime_error %s", t.index, err.c_str());
                    _exit(0);
                }
                free_buffers(b);
                break;
            }
            free_buffers(b);
        }
        long long med = median(metric), medWall = median(wall);
        status("test %d ok runs %d time_ns %lld wall_ns %lld", t.index, (int)metric.size(), med, medWall);
        if (med > cfg.timeLimitNs || medWall > cfg.wallLimitNs) _exit(0);  // time limit: no point in continuing
    }
    status("done");
    _exit(0);
}

}  // namespace judge

// ------------------------------------------------------------------------------------------------
// Launch wrappers (linked with -Xlinker --wrap=<name> for each of these).
// ------------------------------------------------------------------------------------------------
extern "C" {
cudaError_t __real_cudaLaunchKernel(const void*, dim3, dim3, void**, size_t, cudaStream_t);
cudaError_t __real_cudaLaunchKernel_ptsz(const void*, dim3, dim3, void**, size_t, cudaStream_t);
cudaError_t __real_cudaLaunchCooperativeKernel(const void*, dim3, dim3, void**, size_t, cudaStream_t);
cudaError_t __real_cudaLaunchCooperativeKernel_ptsz(const void*, dim3, dim3, void**, size_t, cudaStream_t);
cudaError_t __real_cudaLaunchKernelExC(const cudaLaunchConfig_t*, const void*, void**);
cudaError_t __real_cudaLaunchKernelExC_ptsz(const cudaLaunchConfig_t*, const void*, void**);

cudaError_t __wrap_cudaLaunchKernel(const void* f, dim3 g, dim3 b, void** a, size_t s, cudaStream_t st) {
    judge::LaunchRec* r = judge::begin_launch(st);
    cudaError_t e = __real_cudaLaunchKernel(f, g, b, a, s, st);
    judge::end_launch(r, st);
    return e;
}
cudaError_t __wrap_cudaLaunchKernel_ptsz(const void* f, dim3 g, dim3 b, void** a, size_t s, cudaStream_t st) {
    cudaStream_t es = st ? st : cudaStreamPerThread;
    judge::LaunchRec* r = judge::begin_launch(es);
    cudaError_t e = __real_cudaLaunchKernel_ptsz(f, g, b, a, s, st);
    judge::end_launch(r, es);
    return e;
}
cudaError_t __wrap_cudaLaunchCooperativeKernel(const void* f, dim3 g, dim3 b, void** a, size_t s, cudaStream_t st) {
    judge::LaunchRec* r = judge::begin_launch(st);
    cudaError_t e = __real_cudaLaunchCooperativeKernel(f, g, b, a, s, st);
    judge::end_launch(r, st);
    return e;
}
cudaError_t __wrap_cudaLaunchCooperativeKernel_ptsz(const void* f, dim3 g, dim3 b, void** a, size_t s, cudaStream_t st) {
    cudaStream_t es = st ? st : cudaStreamPerThread;
    judge::LaunchRec* r = judge::begin_launch(es);
    cudaError_t e = __real_cudaLaunchCooperativeKernel_ptsz(f, g, b, a, s, st);
    judge::end_launch(r, es);
    return e;
}
cudaError_t __wrap_cudaLaunchKernelExC(const cudaLaunchConfig_t* c, const void* f, void** a) {
    cudaStream_t st = c ? c->stream : 0;
    judge::LaunchRec* r = judge::begin_launch(st);
    cudaError_t e = __real_cudaLaunchKernelExC(c, f, a);
    judge::end_launch(r, st);
    return e;
}
cudaError_t __wrap_cudaLaunchKernelExC_ptsz(const cudaLaunchConfig_t* c, const void* f, void** a) {
    cudaStream_t st = c && c->stream ? c->stream : cudaStreamPerThread;
    judge::LaunchRec* r = judge::begin_launch(st);
    cudaError_t e = __real_cudaLaunchKernelExC_ptsz(c, f, a);
    judge::end_launch(r, st);
    return e;
}
}
