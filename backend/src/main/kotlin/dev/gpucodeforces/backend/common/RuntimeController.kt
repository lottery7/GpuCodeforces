package dev.gpucodeforces.backend.common

import dev.gpucodeforces.backend.api.RuntimeApi
import dev.gpucodeforces.backend.api.model.RuntimeInfo
import dev.gpucodeforces.backend.api.model.ExecutionMode
import dev.gpucodeforces.backend.submission.JudgeProperties
import dev.gpucodeforces.backend.benchmark.ReferenceMeasurements
import dev.gpucodeforces.backend.processor.ProcessorCatalog
import dev.gpucodeforces.judge.api.Processors
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class RuntimeController(
    private val judgeProperties: JudgeProperties,
    private val processors: ProcessorCatalog,
    private val references: ReferenceMeasurements,
) : RuntimeApi {
    override fun getRuntime(): ResponseEntity<RuntimeInfo> = ResponseEntity.ok(
        RuntimeInfo().executionMode(ExecutionMode.fromValue(judgeProperties.executionMode))
            .gpuName((references.environmentName() ?: processors.model(Processors.GPU_CUDA)).takeUnless { judgeProperties.synthetic }),
    )
}
