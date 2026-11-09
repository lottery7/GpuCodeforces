package dev.gpucodeforces.judge.executor.compile

import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.util.Proc
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Duration

/**
 * Pinned toolchain. Verified once at startup: every submission is compiled by exactly the same nvcc/host compiler
 * and every problem is prepared with the same torch, otherwise the executor refuses to start.
 */
class Toolchain(private val props: ExecutorProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tc = props.toolchain

    lateinit var cudaRelease: String
        private set
    lateinit var nvccVersion: String
        private set
    lateinit var hostCompilerVersion: String
        private set
    var torchVersion: String? = null
        private set

    /** `sys.prefix` of the Python that has torch (a venv outside the system dirs must be mounted into the sandbox). */
    var pythonPrefix: Path? = null
        private set

    /** Short description included in results, e.g. "nvcc 12.8.93 (sm_86), g++ 13.3.0, torch 2.8.0+cu128". */
    val description: String
        get() = "nvcc $nvccVersion (${props.profile.arch}), ${tc.hostCompiler.substringAfterLast('/')} $hostCompilerVersion" +
            (torchVersion?.let { ", torch $it" } ?: "")

    /** Part of every compile cache key: a toolchain change invalidates cached objects. */
    val fingerprint: String get() = "$nvccVersion|$hostCompilerVersion|${props.profile.arch}"

    fun verify() {
        val nvcc = Proc.run(listOf(tc.nvcc, "--version"), Duration.ofSeconds(30))
        check(nvcc.ok) { "nvcc not usable: ${nvcc.describe()}" }
        cudaRelease = Regex("release (\\d+\\.\\d+)").find(nvcc.stdout)?.groupValues?.get(1)
            ?: error("cannot parse nvcc --version: ${nvcc.stdout}")
        nvccVersion = Regex("V(\\d+\\.\\d+\\.\\d+)").find(nvcc.stdout)?.groupValues?.get(1) ?: cudaRelease
        tc.expectedCudaRelease?.let {
            check(cudaRelease == it) { "CUDA release mismatch: nvcc reports $cudaRelease, judge.executor.toolchain.expected-cuda-release=$it" }
        }

        val cxx = Proc.run(listOf(tc.hostCompiler, "-dumpfullversion", "-dumpversion"), Duration.ofSeconds(30))
        check(cxx.ok) { "host compiler not usable: ${cxx.describe()}" }
        hostCompilerVersion = cxx.stdout.trim()
        tc.expectedHostCompilerMajor?.let {
            check(hostCompilerVersion.substringBefore('.') == it) {
                "host compiler mismatch: ${tc.hostCompiler} is $hostCompilerVersion, expected major $it"
            }
        }

        val torch = Proc.run(
            listOf(tc.python, "-c", "import sys, torch; print(torch.__version__); print(torch.cuda.is_available()); print(sys.prefix)"),
            Duration.ofSeconds(120),
        )
        if (torch.ok) {
            val lines = torch.stdout.lines().map { it.trim() }
            torchVersion = lines[0]
            pythonPrefix = Path.of(lines[2])
            if (lines[1] != "True") {
                log.warn("torch reports CUDA unavailable: references will be computed on CPU")
            }
        } else {
            log.warn("torch is not importable ({}); problem preparation will fail", torch.describe())
        }
        tc.expectedTorchVersion?.let {
            check(torchVersion?.startsWith(it) == true) { "torch version mismatch: found $torchVersion, expected $it" }
        }
        log.info("Toolchain verified: {}", description)
    }
}
