package com.example.gpuplatform.problem.domain

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Structured metadata parsed from a problem Markdown file's YAML front matter.
 *
 * `interface`, `constraints` and `execution` are strongly typed. Test generation
 * and evaluation are not modelled here: they are provided by the `solution.py`
 * and `tests.py` scripts stored alongside `problem.md` (see `Problem.solutionPy`
 * and `Problem.testsPy`).
 */
data class ProblemMetadata(
    @JsonProperty("interface")
    val interfaceSpec: InterfaceSpec = InterfaceSpec(),
    val constraints: Map<String, ConstraintSpec> = emptyMap(),
    val execution: ExecutionSpec = ExecutionSpec(),
)

data class InterfaceSpec(
    val functionName: String = "",
    val parameters: List<ParameterSpec> = emptyList(),
)

data class ParameterSpec(
    val name: String = "",
    val type: String = "",
    val direction: String = "",
)

/**
 * Per-variable constraints. Only `min`/`max` are modeled; the map is extensible
 * via unknown YAML keys only if strictly needed in the future.
 */
data class ConstraintSpec(
    val min: Any? = null,
    val max: Any? = null,
)

data class ExecutionSpec(
    val gpu: GpuSpec = GpuSpec(),
    val limits: LimitsSpec = LimitsSpec(),
)

data class GpuSpec(
    val minComputeCapability: String? = null,
    val minMemoryMb: Long? = null,
)

data class LimitsSpec(
    val gpuTimeMs: Long? = null,
    val hostMemoryMb: Long? = null,
    val deviceMemoryMb: Long? = null,
)