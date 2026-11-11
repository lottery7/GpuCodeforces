package dev.gpucodeforces.judge.executor.processor

/** The processors this executor judges on, in the order of its configuration. Closed at shutdown. */
class ProcessorBackends(val all: List<ProcessorBackend>) : AutoCloseable {
    override fun close() {
        all.forEach { it.close() }
    }
}
