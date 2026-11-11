package dev.gpucodeforces.judge.api

import java.math.BigDecimal
import java.math.BigInteger

/**
 * Structural validation of problem specs and submissions. Used by the gateway (reject early with 400)
 * and again by executors (never trust the queue).
 */
object SpecValidator {

    data class Limits(
        val maxSourceBytes: Int = 128 * 1024,
        /** Max tests a generator may declare; checked by executors once the generator has run. */
        val maxTests: Int = 200,
        /** Upper bound on bytes of all arrays of a single test. */
        val maxTestBytes: Long = 4L shl 30,
        val maxTimeLimitMs: Long = 30_000,
        val maxMemoryLimitMb: Long = 32 * 1024,
        val maxGeneratorBytes: Int = 256 * 1024,
    )

    /** Bounds on free-form fields, so no part of a request can be arbitrarily large. */
    const val MAX_NAME_LENGTH = 128
    private const val MAX_SIZE_EXPRESSION_LENGTH = 256
    private const val MAX_LIST_ENTRIES = 64

    private val ID = Regex("[A-Za-z0-9._-]{1,128}")

    /** Submission and problem ids appear in file names, queue messages and logs. */
    fun isValidId(id: String): Boolean = ID.matches(id)
    private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
    private val RESERVED = setOf(
        "main", "alignas", "alignof", "asm", "auto", "bool", "break", "case", "catch", "char", "class", "const",
        "constexpr", "continue", "default", "delete", "do", "double", "else", "enum", "explicit", "extern", "false",
        "float", "for", "friend", "goto", "if", "inline", "int", "long", "mutable", "namespace", "new", "noexcept",
        "nullptr", "operator", "private", "protected", "public", "register", "return", "short", "signed", "sizeof",
        "static", "struct", "switch", "template", "this", "throw", "true", "try", "typedef", "typename", "union",
        "unsigned", "using", "virtual", "void", "volatile", "while", "dim3", "half",
    )

    fun validateSubmission(req: SubmissionRequest, limits: Limits = Limits()): List<String> = buildList {
        if (!ID.matches(req.submissionId)) add("submissionId must match ${ID.pattern}")
        val sourceBytes = req.source.toByteArray().size
        if (req.source.isBlank()) add("source is empty")
        if (sourceBytes > limits.maxSourceBytes) add("source is $sourceBytes bytes, limit ${limits.maxSourceBytes}")
        if (req.source.contains('\u0000')) add("source contains NUL bytes")
        addAll(validateTarget(req.problem, req.processor, req.language))
        addAll(validateProblem(req.problem, limits).map { "problem: $it" })
    }

    /** Whether a submission in [language] can be judged on [processor] for this problem. */
    fun validateTarget(problem: ProblemSpec, processor: String, language: String): List<String> = buildList {
        val declared = problem.declaredProcessors()
        val spec = problem.processorSpec(processor)
        if (spec == null) {
            add("processor '${processor.take(MAX_NAME_LENGTH)}' is not one the problem declares: ${declared.joinToString { it.id }}")
            return@buildList
        }
        if (language !in spec.languages) {
            add("language '${language.take(MAX_NAME_LENGTH)}' is not allowed on $processor, allowed: ${spec.languages.joinToString()}")
        }
        if (problem.scoreLimit(spec) == null) add("processor $processor has no limit in the problem")
    }

    fun validateProblem(spec: ProblemSpec, limits: Limits = Limits()): List<String> {
        val errors = mutableListOf<String>()
        if (!ID.matches(spec.id)) errors += "id must match ${ID.pattern}"
        validateSignature(spec.signature, errors)
        if (spec.version.length > MAX_NAME_LENGTH) errors += "version is longer than $MAX_NAME_LENGTH characters"
        if (spec.generator.source.isBlank()) errors += "generator.source is empty"
        val generatorBytes = spec.generator.source.toByteArray().size
        if (generatorBytes > limits.maxGeneratorBytes) errors += "generator.source is $generatorBytes bytes, limit ${limits.maxGeneratorBytes}"
        if (spec.customTests.isNotEmpty() && !spec.allowCustomTests) errors += "custom tests are disabled"
        if (spec.customOnly && spec.customTests.size != 1) errors += "customOnly requires exactly one test"
        if (spec.customTests.size > 32) errors += "at most 32 custom tests are allowed"
        spec.customTests.take(32).forEach { errors += validateCustomTest(spec.signature, it) }

        with(spec.checker) {
            if (!(rtol >= 0 && rtol.isFinite())) errors += "checker.rtol must be a finite non-negative number"
            if (!(atol >= 0 && atol.isFinite())) errors += "checker.atol must be a finite non-negative number"
        }
        with(spec.limits) {
            if (timeLimitMs !in 1..limits.maxTimeLimitMs) errors += "limits.timeLimitMs must be in 1..${limits.maxTimeLimitMs}"
            if (wallTimeLimitMs != null && wallTimeLimitMs < timeLimitMs) errors += "limits.wallTimeLimitMs must be >= timeLimitMs"
            if (effectiveWallTimeLimitMs > 4 * limits.maxTimeLimitMs) errors += "limits.wallTimeLimitMs is too large"
            if (memoryLimitMb !in 16..limits.maxMemoryLimitMb) errors += "limits.memoryLimitMb must be in 16..${limits.maxMemoryLimitMb}"
            if (gpuMemoryLimitMb != null && gpuMemoryLimitMb !in 16..limits.maxMemoryLimitMb) {
                errors += "limits.gpuMemoryLimitMb must be in 16..${limits.maxMemoryLimitMb}"
            }
        }
        with(spec.timing) {
            if (warmupRuns !in 0..10) errors += "timing.warmupRuns must be in 0..10"
            if (maxRuns !in 1..20) errors += "timing.maxRuns must be in 1..20"
        }
        with(spec.policy) {
            if (listOf(allowHeaders, forbidHeaders, forbidSass, allowSass).any { it.size > MAX_LIST_ENTRIES }) {
                errors += "policy: a list has more than $MAX_LIST_ENTRIES entries"
            }
            (allowHeaders + forbidHeaders).filter { !HEADER.matches(it) }.forEach { errors += "policy: invalid header '${it.take(MAX_NAME_LENGTH)}'" }
            (forbidSass + allowSass).filter { !SASS.matches(it) }.forEach { errors += "policy: invalid SASS opcode '${it.take(MAX_NAME_LENGTH)}'" }
        }
        validateProcessors(spec, limits, errors)
        with(spec.compile) {
            if (arch != null && !CompileSpec.isArch(arch)) errors += "compile.arch '${arch.take(MAX_NAME_LENGTH)}' is not an architecture like sm_86"
            if (flags.orEmpty().size > MAX_LIST_ENTRIES) errors += "compile: more than $MAX_LIST_ENTRIES flags"
            flags.orEmpty().filter { flag -> COMPILE_FLAGS.none { it.matches(flag) } }
                .forEach { errors += "compile: flag '${it.take(MAX_NAME_LENGTH)}' is not allowed" }
        }
        return errors
    }

    /** Read ports of an array on `rtl`: each is a port pair of the judge's memory, so more than a few is pointless. */
    val RTL_READ_PORTS = 1..8

    private fun validateProcessors(spec: ProblemSpec, limits: Limits, errors: MutableList<String>) {
        if (spec.processors.size > Processors.all.size) errors += "processors: more than ${Processors.all.size} entries"
        val seen = mutableSetOf<String>()
        for (p in spec.processors.take(MAX_LIST_ENTRIES)) {
            val id = p.id.take(MAX_NAME_LENGTH)
            val info = Processors.find(p.id)
            if (info == null) {
                errors += "processors: unknown processor '$id', known: ${Processors.ids.joinToString()}"
                continue
            }
            val where = "processor $id"
            if (!seen.add(p.id)) errors += "$where: declared twice"
            if (p.languages.isEmpty()) errors += "$where: languages is empty"
            p.languages.filter { info.language(it) == null }.distinct().forEach {
                errors += "$where: language '${it.take(MAX_NAME_LENGTH)}' is not one of ${info.languages.joinToString { l -> l.id }}"
            }
            if (p.languages.size != p.languages.toSet().size) errors += "$where: a language is listed twice"
            when {
                p.limit == null -> if (p.id != Processors.GPU_CUDA) errors += "$where: limit is required"
                !(p.limit > 0 && p.limit.isFinite()) -> errors += "$where: limit must be a positive number"
                p.id == Processors.GPU_CUDA && p.limit > limits.maxTimeLimitMs -> errors += "$where: limit must be at most ${limits.maxTimeLimitMs} ms"
            }
            if (p.rtl != null && p.id != Processors.RTL) errors += "$where: rtl settings belong to processor ${Processors.RTL} only"
            if (p.id == Processors.RTL) validateRtl(spec.signature, p.rtl ?: RtlSettings(), errors)
            if (p.id == Processors.CUDA_SIM) {
                // the simulator counts only the GPU's cycles: work on host data would be free
                spec.signature.arrays.filter { it.memory != MemorySpace.DEVICE }.forEach {
                    errors += "$where: array '${it.name}' must be in DEVICE memory: work on the host costs no cycles in the simulator"
                }
            }
        }
    }

    private fun validateRtl(sig: Signature, settings: RtlSettings, errors: MutableList<String>) {
        val where = "processor ${Processors.RTL}"
        if (settings.readPorts !in RTL_READ_PORTS) errors += "$where: rtl.readPorts must be in $RTL_READ_PORTS"
        if (settings.dataMode == RtlDataMode.STREAM) {
            sig.params.filter { it.role == ParamRole.INOUT }.forEach {
                errors += "$where: param '${it.name}' is INOUT, which the STREAM data mode has no ports for"
            }
        }
        for (p in sig.params) {
            if (p.name in RtlSignature.KEYWORDS) errors += "$where: param '${p.name}' is a SystemVerilog keyword"
            if (p.name in RtlSignature.CONTROL_PORTS) errors += "$where: param '${p.name}' takes the name of a control port"
        }
        RtlSignature.ports(sig, settings).groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach {
            errors += "$where: two ports of the module would be named '$it'; rename a param"
        }
    }

    /** Limits of a [CustomTest]: such tests travel inline with the submission, so they are small. */
    fun validateCustomTest(sig: Signature, test: CustomTest): List<String> = buildList {
        if (test.scalars.keys != sig.scalars.map { it.name }.toSet()) add("invalid scalar names")
        val inputs = sig.arrays.filter { it.role == ParamRole.INPUT || it.role == ParamRole.INOUT }
        if (test.inputs.keys != inputs.map { it.name }.toSet()) add("invalid input names")
        if (test.inputs.values.sumOf { it.size.toLong() } > 8192) add("custom test exceeds 8192 input elements")
        for (param in sig.scalars) test.scalars[param.name]?.let { value ->
            if (!validNumber(value, param.type)) add("invalid scalar ${param.name}")
        }
        if (isNotEmpty()) return@buildList
        val counts = try {
            elementCounts(sig, test.scalars)
        } catch (e: IllegalArgumentException) {
            add(e.message ?: "invalid sizes")
            return@buildList
        } catch (e: ArithmeticException) {
            add(e.message ?: "size overflow")
            return@buildList
        }
        if (sig.arrays.any { counts.getValue(it.name) !in 1..8192 }) add("custom array sizes must be in 1..8192")
        for (param in inputs) {
            val values = test.inputs.getValue(param.name)
            if (values.size.toLong() != counts.getValue(param.name)) add("invalid size of ${param.name}")
            if (values.any { !validNumber(it, param.type) }) add("invalid values of ${param.name}")
        }
    }

    private fun validNumber(value: Number, type: DType): Boolean {
        if (!value.toDouble().isFinite()) return false
        if (type.floating) return when (type) {
            DType.FLOAT16 -> kotlin.math.abs(value.toDouble()) <= 65504
            DType.BFLOAT16, DType.FLOAT32 -> value.toFloat().isFinite()
            else -> true
        }
        if (!isIntegral(value)) return false
        val number = BigDecimal(value.toString()).toBigInteger()
        val bits = type.bytes * 8
        val unsigned = type == DType.UINT8 || type == DType.UINT32 || type == DType.UINT64
        val min = if (unsigned) BigInteger.ZERO else BigInteger.ONE.shiftLeft(bits - 1).negate()
        val max = if (unsigned && type != DType.UINT64) BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
            else BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE)
        return number in min..max
    }

    private val HEADER = Regex("[A-Za-z0-9_./+-]{1,128}")
    private val SASS = Regex("[A-Z0-9_]{1,32}")

    /**
     * nvcc flags a problem may set. An allow-list: flags reach the compiler command line, and options that read
     * or write files, pass options through to the host compiler or linker, or pick the architecture must not.
     */
    private val COMPILE_FLAGS = listOf(
        Regex("-O[0-3]"),
        Regex("-std=c\\+\\+(11|14|17|20)"),
        Regex("-D[A-Za-z_][A-Za-z0-9_]{0,63}(=[A-Za-z0-9_.+-]{0,64})?"),
        Regex("--use_fast_math"),
        Regex("--fmad=(true|false)"),
        Regex("--expt-relaxed-constexpr"),
        Regex("--expt-extended-lambda"),
    )

    private fun validateSignature(sig: Signature, errors: MutableList<String>) {
        if (!IDENT.matches(sig.function) || sig.function in RESERVED || sig.function.startsWith("judge")) {
            errors += "signature.function '${sig.function}' is not a valid name"
        }
        if (sig.params.isEmpty()) errors += "signature.params is empty"
        if (sig.params.size > MAX_LIST_ENTRIES) errors += "signature has more than $MAX_LIST_ENTRIES params"
        val seen = mutableSetOf<String>()
        for (p in sig.params) {
            val where = "param '${p.name}'"
            if (!IDENT.matches(p.name) || p.name in RESERVED || p.name.startsWith("judge") || p.name.startsWith("__")) {
                errors += "$where: not a valid C identifier"
            }
            if (!seen.add(p.name)) errors += "$where: duplicate name"
            if (p.role == ParamRole.SCALAR) {
                if (!p.type.scalarAllowed) errors += "$where: type ${p.type} is not allowed for scalars"
                if (p.memory != null || p.size != null) errors += "$where: scalars must not have memory/size"
            } else {
                if (p.memory == null) errors += "$where: memory (DEVICE/HOST) is required for arrays"
                if (p.size == null) {
                    errors += "$where: size is required for arrays"
                } else if (p.size.length > MAX_SIZE_EXPRESSION_LENGTH) {
                    // the parser is recursive: an unbounded expression is an unbounded stack
                    errors += "$where: size expression is longer than $MAX_SIZE_EXPRESSION_LENGTH characters"
                } else {
                    try {
                        val expr = SizeExpr.parse(p.size)
                        for (id in expr.identifiers) {
                            val ref = sig.params.find { it.name == id }
                            if (ref == null || ref.role != ParamRole.SCALAR || ref.type.floating) {
                                errors += "$where: size refers to '$id' which is not an integer scalar param"
                            }
                        }
                    } catch (e: IllegalArgumentException) {
                        errors += "$where: ${e.message}"
                    }
                }
            }
        }
        if (sig.outputs.isEmpty()) errors += "signature must have at least one OUTPUT or INOUT param"
    }

    /** Element count of every array param for a test with the given scalar values. */
    fun elementCounts(sig: Signature, scalars: Map<String, Number>): Map<String, Long> {
        val vars = sig.scalars.filter { !it.type.floating }.associate { p ->
            val v = scalars[p.name] ?: throw IllegalArgumentException("missing scalar ${p.name}")
            p.name to v.toLong()
        }
        return sig.arrays.associate { p -> p.name to SizeExpr.parse(p.size!!).eval(vars) }
    }

    fun isIntegral(v: Number): Boolean = when (v) {
        is Int, is Long, is Short, is Byte, is BigInteger -> true
        is BigDecimal -> v.stripTrailingZeros().scale() <= 0
        else -> v.toDouble().let { it.isFinite() && it == Math.rint(it) }
    }
}
