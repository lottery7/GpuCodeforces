package dev.gpucodeforces.judge.executor.problem

import dev.gpucodeforces.judge.executor.config.ExecutorProperties
import dev.gpucodeforces.judge.executor.util.Proc
import org.intellij.lang.annotations.Language
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Duration

/**
 * The Python with PyTorch that generates tests, on every executor whatever processors it judges. Verified once at
 * startup: every problem is prepared with the same torch.
 */
class PrepareRuntime(private val props: ExecutorProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    var torchVersion: String? = null
        private set

    /** Whether torch can compute on a GPU here; without one references are computed on the CPU. */
    var cudaAvailable: Boolean = false
        private set

    /** `sys.prefix` of the Python that has torch (a venv outside the system dirs must be mounted into the sandbox). */
    var pythonPrefix: Path? = null
        private set

    fun verify() {
        val tc = props.toolchain
        val torch = Proc.run(listOf(tc.python, "-c", TORCH_PROBE), Duration.ofSeconds(120))
        if (torch.ok) {
            val lines = torch.stdout.lines().map { it.trim() }
            check(lines.size >= 3) { "unexpected torch probe output: $lines" }
            torchVersion = lines[0]
            cudaAvailable = lines[1] == "True"
            pythonPrefix = Path.of(lines[2])
            if (!cudaAvailable) log.info("torch reports CUDA unavailable: references will be computed on CPU")
        } else {
            log.warn("torch is not importable ({}); problem preparation will fail", torch.describe())
        }
        tc.expectedTorchVersion?.let {
            check(torchVersion?.startsWith(it) == true) { "torch version mismatch: found $torchVersion, expected $it" }
        }
        log.info("Test generation runtime: torch {} (CUDA {})", torchVersion, if (cudaAvailable) "available" else "unavailable")
    }

    private companion object {
        /** Prints the torch version, whether CUDA is usable, and the interpreter's prefix, one per line. */
        @Language("Python")
        const val TORCH_PROBE = "import sys, torch; print(torch.__version__); print(torch.cuda.is_available()); print(sys.prefix)"
    }
}
