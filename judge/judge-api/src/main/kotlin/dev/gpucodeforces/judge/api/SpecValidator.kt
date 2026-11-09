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
        val maxTests: Int = 200,
        /** Upper bound on bytes of all arrays of a single test. */
        val maxTestBytes: Long = 4L shl 30,
        val maxTimeLimitMs: Long = 30_000,
        val maxMemoryLimitMb: Long = 32 * 1024,
        /** Max total number of inline sample values per test. */
        val maxInlineValues: Int = 100_000,
        val maxGeneratorBytes: Int = 256 * 1024,
    )

    /** Bounds on free-form fields, so no part of a request can be arbitrarily large. */
    private const val MAX_NAME_LENGTH = 128
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
        addAll(validateProblem(req.problem, limits).map { "problem: $it" })
    }

    fun validateProblem(spec: ProblemSpec, limits: Limits = Limits()): List<String> {
        val errors = mutableListOf<String>()
        if (!ID.matches(spec.id)) errors += "id must match ${ID.pattern}"
        validateSignature(spec.signature, errors)
        if (errors.isNotEmpty()) return errors // tests can't be checked against a broken signature

        if (spec.version.length > MAX_NAME_LENGTH) errors += "version is longer than $MAX_NAME_LENGTH characters"
        if (spec.generator.source.isBlank()) errors += "generator.source is empty"
        val generatorBytes = spec.generator.source.toByteArray().size
        if (generatorBytes > limits.maxGeneratorBytes) errors += "generator.source is $generatorBytes bytes, limit ${limits.maxGeneratorBytes}"
        if (spec.tests.isEmpty()) errors += "at least one test is required"
        if (spec.tests.size > limits.maxTests) errors += "too many tests: ${spec.tests.size} > ${limits.maxTests}"
        val names = mutableSetOf<String>()
        spec.tests.forEachIndexed { i, t ->
            val where = "tests[$i] '${t.name.take(MAX_NAME_LENGTH)}'"
            if (t.name.isBlank()) errors += "tests[$i]: name is empty"
            if (t.name.length > MAX_NAME_LENGTH) errors += "tests[$i]: name is longer than $MAX_NAME_LENGTH characters"
            if (!names.add(t.name)) errors += "$where: duplicate test name"
            validateTest(spec.signature, t, where, limits, errors)
        }

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
        with(spec.compile) {
            if (arch != null && !CompileSpec.isArch(arch)) errors += "compile.arch '${arch.take(MAX_NAME_LENGTH)}' is not an architecture like sm_86"
            if (flags.orEmpty().size > MAX_LIST_ENTRIES) errors += "compile: more than $MAX_LIST_ENTRIES flags"
            flags.orEmpty().filter { flag -> COMPILE_FLAGS.none { it.matches(flag) } }
                .forEach { errors += "compile: flag '${it.take(MAX_NAME_LENGTH)}' is not allowed" }
        }
        return errors
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

    private fun validateTest(sig: Signature, t: TestSpec, where: String, limits: Limits, errors: MutableList<String>) {
        val scalarNames = sig.scalars.map { it.name }.toSet()
        val missing = scalarNames - t.scalars.keys
        val extra = t.scalars.keys - scalarNames
        if (missing.isNotEmpty()) errors += "$where: missing scalars $missing"
        if (extra.isNotEmpty()) errors += "$where: unknown scalars $extra"
        if (missing.isNotEmpty() || extra.isNotEmpty()) return

        for (p in sig.scalars) {
            val v = t.scalars.getValue(p.name)
            if (!p.type.floating && !isIntegral(v)) errors += "$where: scalar ${p.name} must be an integer"
        }
        val sizes = try {
            elementCounts(sig, t)
        } catch (e: IllegalArgumentException) {
            errors += "$where: ${e.message}"
            return
        } catch (e: ArithmeticException) {
            errors += "$where: ${e.message}"
            return
        }
        var bytes = 0L
        for (p in sig.arrays) {
            val n = sizes.getValue(p.name)
            if (n < 0) errors += "$where: size of ${p.name} is negative ($n)"
            // per array, so that a sum which overflows cannot wrap back under the limit
            if (n > limits.maxTestBytes / p.type.bytes) {
                errors += "$where: ${p.name} alone exceeds the limit of ${limits.maxTestBytes} bytes"
                return
            }
            bytes += n * p.type.bytes
        }
        if (bytes > limits.maxTestBytes) errors += "$where: test data is $bytes bytes, limit ${limits.maxTestBytes}"

        val data = t.data ?: return
        val inputs = sig.arrays.filter { it.role == ParamRole.INPUT || it.role == ParamRole.INOUT }.map { it.name }.toSet()
        if (data.keys != inputs) errors += "$where: data must contain exactly the input arrays $inputs"
        var total = 0
        for ((name, values) in data) {
            val expected = sizes[name] ?: continue
            if (values.size.toLong() != expected) errors += "$where: data.$name has ${values.size} values, expected $expected"
            total += values.size
        }
        if (total > limits.maxInlineValues) errors += "$where: too many inline values ($total > ${limits.maxInlineValues})"
    }

    /** Element count of every array param for the given test. */
    fun elementCounts(sig: Signature, t: TestSpec): Map<String, Long> {
        val vars = sig.scalars.filter { !it.type.floating }.associate { p ->
            val v = t.scalars[p.name] ?: throw IllegalArgumentException("missing scalar ${p.name}")
            p.name to v.toLong()
        }
        return sig.arrays.associate { p -> p.name to SizeExpr.parse(p.size!!).eval(vars) }
    }

    private fun isIntegral(v: Number): Boolean = when (v) {
        is Int, is Long, is Short, is Byte, is BigInteger -> true
        is BigDecimal -> v.stripTrailingZeros().scale() <= 0
        else -> v.toDouble().let { it.isFinite() && it == Math.rint(it) }
    }
}
