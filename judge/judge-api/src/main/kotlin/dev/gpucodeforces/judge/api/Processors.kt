package dev.gpucodeforces.judge.api

/** What a [Score] counts. The unit shown to people follows from the type; the contract carries no unit string. */
enum class ScoreType {
    /** Measured time of one `solve` call on a real GPU, ms. */
    TIME_MS,

    /** Clock cycles of a cycle-level simulator. */
    CYCLES,

    /** Cycles until `done` times the logic depth times the transistors of the synthesized circuit (`rtl`). */
    CYCLES_X_DEPTH_X_TRANSISTORS,

    /** Instructions plus weighted cache misses, an estimate of cycles in the spirit of Cachegrind. */
    WEIGHTED_INSTRUCTIONS,

    /** Instructions an instruction-level simulator executed, each counted once. */
    INSTRUCTIONS,
}

/** A result of a submission or a test: less is better, and only scores of one processor are comparable. */
data class Score(val value: Double, val type: ScoreType)

data class LanguageInfo(
    /** Used in submissions and problem packages, e.g. `cuda`. */
    val id: String,
    val title: String,
    /** Of the contestant's file, with the dot: `.cu`. */
    val extension: String,
)

data class ProcessorInfo(
    /** Routing key and queue suffix, so it follows [Topology.PROFILE_NAME]. */
    val id: String,
    val title: String,
    val scoreType: ScoreType,
    val languages: List<LanguageInfo>,
    /** False only for `gpu-cuda`: every other processor runs in an emulator on a machine without a GPU. */
    val emulated: Boolean,
) {
    fun language(id: String): LanguageInfo? = languages.find { it.id == id }
}

/** Ids of the languages of [Processors]. */
object Languages {
    const val CUDA = "cuda"
    const val SYSTEMVERILOG = "systemverilog"
    const val C = "c"
    const val CPP = "cpp"
    const val ASM = "asm"
}

/**
 * The static catalog of processors, shared by the gateway, executors and the backend. All of them are listed
 * from the start; a gateway accepts submissions only for the processors in its configuration.
 */
object Processors {
    const val GPU_CUDA = "gpu-cuda"
    const val RTL = "rtl"
    const val RISCV_RVV = "riscv-rvv"
    const val CUDA_SIM = "cuda-sim"
    const val CORAL_NPU = "coral-npu"

    private val CUDA = LanguageInfo(Languages.CUDA, "CUDA C++", ".cu")
    private val SYSTEMVERILOG = LanguageInfo(Languages.SYSTEMVERILOG, "SystemVerilog", ".sv")
    private val C = LanguageInfo(Languages.C, "C", ".c")
    private val CPP = LanguageInfo(Languages.CPP, "C++", ".cpp")
    private val ASM = LanguageInfo(Languages.ASM, "RISC-V assembly", ".S")

    val all: List<ProcessorInfo> = listOf(
        ProcessorInfo(GPU_CUDA, "NVIDIA GPU", ScoreType.TIME_MS, listOf(CUDA), emulated = false),
        ProcessorInfo(RTL, "RTL (Verilator + Yosys)", ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS, listOf(SYSTEMVERILOG), emulated = true),
        ProcessorInfo(RISCV_RVV, "RISC-V RVV (QEMU)", ScoreType.WEIGHTED_INSTRUCTIONS, listOf(C, CPP, ASM), emulated = true),
        ProcessorInfo(CUDA_SIM, "NVIDIA GPU simulator (GPGPU-Sim)", ScoreType.CYCLES, listOf(CUDA), emulated = true),
        ProcessorInfo(CORAL_NPU, "Coral NPU (Renode)", ScoreType.INSTRUCTIONS, listOf(C, CPP), emulated = true),
    )

    private val byId = all.associateBy { it.id }

    fun find(id: String): ProcessorInfo? = byId[id]

    fun get(id: String): ProcessorInfo = find(id) ?: throw IllegalArgumentException("unknown processor '$id'")

    val ids: List<String> get() = all.map { it.id }
}
