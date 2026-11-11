package dev.gpucodeforces.judge.executor.processor.gpucuda

/**
 * A status line of the harness (see runtime.h), without the nonce. Being one of these says nothing about who wrote
 * the line: the solution shares the process and may print them too, so [HarnessRunner] decides whether the harness
 * could have written it at that moment.
 */
sealed interface HarnessLine {
    /** The CUDA context is up; tests follow. */
    data object Ready : HarnessLine

    /** The harness reads the inputs of [test]. */
    data class Load(val test: Int) : HarnessLine

    /** The harness calls `solve` on [test]; [first] for the first call of that test. */
    data class Run(val test: Int, val first: Boolean) : HarnessLine

    data class TestOk(val test: Int, val timeNs: Long, val wallNs: Long, val runs: Int?) : HarnessLine

    /** A CUDA error in `solve`; the harness exits after it. */
    data class RuntimeError(val test: Int, val details: String) : HarnessLine

    /** The harness could not allocate the arrays of [test]; it exits after it. */
    data class AllocFailed(val test: Int, val details: String) : HarnessLine

    /** The harness cannot go on. */
    data class Fatal(val reason: String) : HarnessLine

    /** Every test was run. */
    data object Done : HarnessLine

    companion object {
        /** Null for a line the harness never writes. Only the first word of `ready` and `done` matters. */
        fun parse(line: String): HarnessLine? {
            val parts = line.split(' ')
            val test = parts.getOrNull(1)?.toIntOrNull()
            return when (parts[0]) {
                "ready" -> Ready
                "load" -> test?.let(::Load)
                "run" -> test?.let { Run(it, first = parts.getOrNull(2) == "0") }
                "test" -> test?.let { result(it, parts.getOrNull(2), parts.drop(3)) }
                "fatal" -> Fatal(parts.drop(1).joinToString(" "))
                "done" -> Done
                else -> null
            }
        }

        private fun result(test: Int, kind: String?, rest: List<String>): HarnessLine? = when (kind) {
            "ok" -> {
                val values = rest.chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
                val timeNs = values["time_ns"]?.toLongOrNull()
                val wallNs = values["wall_ns"]?.toLongOrNull()
                if (timeNs == null || wallNs == null) null else TestOk(test, timeNs, wallNs, values["runs"]?.toIntOrNull())
            }
            "runtime_error" -> RuntimeError(test, rest.joinToString(" "))
            "alloc_failed" -> AllocFailed(test, rest.joinToString(" "))
            else -> null
        }
    }
}
