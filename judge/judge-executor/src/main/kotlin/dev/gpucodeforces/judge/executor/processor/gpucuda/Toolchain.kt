package dev.gpucodeforces.judge.executor.processor.gpucuda

import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.problem.PrepareRuntime
import dev.gpucodeforces.judge.executor.util.Proc
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Pinned CUDA toolchain of `gpu-cuda`. Verified once at startup: every submission is compiled by exactly the same
 * nvcc/host compiler, otherwise the executor refuses to start. Tests are prepared by [runtime], verified on its own.
 */
class Toolchain(private val props: ExecutorProperties, private val runtime: PrepareRuntime) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tc = props.toolchain

    lateinit var cudaRelease: String
        private set
    lateinit var nvccVersion: String
        private set
    lateinit var hostCompilerVersion: String
        private set
    /** Short description included in results, e.g. "nvcc 12.8.93 (sm_86), g++ 13.3.0, torch 2.8.0+cu128". */
    val description: String
        get() = "nvcc $nvccVersion (${props.profile.arch}), ${tc.hostCompiler.substringAfterLast('/')} $hostCompilerVersion" +
            (runtime.torchVersion?.let { ", torch $it" } ?: "")

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

        log.info("Toolchain verified: {}", description)
    }
}
