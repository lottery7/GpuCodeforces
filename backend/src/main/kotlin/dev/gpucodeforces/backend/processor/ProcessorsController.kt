package dev.gpucodeforces.backend.processor

import dev.gpucodeforces.backend.api.ProcessorsApi
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import dev.gpucodeforces.backend.api.model.Processor as ProcessorApiModel

@RestController
class ProcessorsController(private val catalog: ProcessorCatalog) : ProcessorsApi {
    override fun listProcessors(): ResponseEntity<List<ProcessorApiModel>> =
        ResponseEntity.ok(catalog.enabled.map { it.toApiModel(catalog.model(it.id)) })
}
