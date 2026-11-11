package dev.gpucodeforces.backend.processor

import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.judge.api.ProcessorInfo
import dev.gpucodeforces.judge.api.Processors
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service

/**
 * Processors of this platform: which of the judge's catalog it offers, and the model a person is shown for each
 * (for `gpu-cuda` the GPU of the executors, e.g. "NVIDIA GeForce RTX 3060").
 */
@ConfigurationProperties("platform.processors")
data class ProcessorProperties(
    val enabled: List<String> = listOf(Processors.GPU_CUDA),
    /** Processor id -> model; a processor without one shows its title. */
    val models: Map<String, String> = emptyMap(),
) {
    init {
        require(enabled.isNotEmpty()) { "platform.processors.enabled is empty" }
        (enabled + models.keys).filter { Processors.find(it) == null }.forEach {
            throw IllegalArgumentException("platform.processors: unknown processor '$it', known: ${Processors.ids}")
        }
    }
}

/** The static catalog of judge-api filtered by [ProcessorProperties]. No state: the backend never asks the gateway. */
@Service
class ProcessorCatalog(private val properties: ProcessorProperties) {
    val enabled: List<ProcessorInfo> = Processors.all.filter { it.id in properties.enabled }

    fun isEnabled(id: String): Boolean = id in properties.enabled

    fun model(id: String): String = properties.models[id] ?: Processors.get(id).title

    /** A processor a request names; 400 unless this platform offers it. */
    fun require(id: String): ProcessorInfo =
        enabled.find { it.id == id } ?: throw BadRequestException("Процессор $id недоступен на платформе")
}
