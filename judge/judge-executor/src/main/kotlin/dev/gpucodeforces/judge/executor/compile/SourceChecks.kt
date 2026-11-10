package dev.gpucodeforces.judge.executor.compile

/** Static checks of the contestant's source and of compiler artifacts. Each returns human-readable violations. */
object SourceChecks {

    /** `#include` allow-list and inline asm ban on the raw source (fast, readable messages). */
    fun checkSource(source: String, policy: EffectivePolicy): List<String> {
        val violations = mutableListOf<String>()
        val noComments = stripComments(joinContinuations(source).replace("%:", "#"))
        noComments.lines().forEachIndexed { i, rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith("#")) return@forEachIndexed
            val directive = line.removePrefix("#").trimStart()
            val name = directive.takeWhile { it.isLetter() || it == '_' }
            when (name) {
                "include" -> {
                    val arg = directive.removePrefix("include").trim()
                    val m = INCLUDE_ARG.matchEntire(arg)
                    when {
                        m == null -> violations += "line ${i + 1}: only #include <header> with a literal name is allowed"
                        m.groupValues[1] == "\"" -> violations += "line ${i + 1}: local includes (\"${m.groupValues[2]}\") are not allowed, the solution is a single file"
                        !policy.headerAllowed(m.groupValues[2]) -> violations += "line ${i + 1}: header <${m.groupValues[2]}> is not allowed"
                    }
                }
                "include_next", "import", "embed" -> violations += "line ${i + 1}: #$name is not allowed"
            }
        }
        if (!policy.allowInlineAsm) {
            val code = stripStrings(noComments)
            code.lines().forEachIndexed { i, line ->
                if (ASM.containsMatchIn(line)) violations += "line ${i + 1}: inline assembly (asm) is not allowed"
            }
        }
        return violations
    }

    /**
     * Headers actually opened by the compiler (from the -MD dependency file). Unlike the source scan this cannot
     * be fooled by macros or by a path that merely looks allowed.
     */
    fun checkDependencies(depFile: String, policy: EffectivePolicy): List<String> {
        val paths = parseDepFile(depFile)
        return paths.drop(1) // first entry is the source itself
            .filter { p -> policy.forbiddenPathFragments.any { it in p } || policy.forbiddenHeaders.any { p.endsWith("/$it") } }
            .distinct()
            .map { "uses a forbidden library header: ${it.substringAfter("/include/")}" }
    }

    fun parseDepFile(text: String): List<String> =
        text.replace("\\\n", " ").substringAfter(':').split(WHITESPACE).filter { it.isNotBlank() && it != "\\" }

    /** Opcodes of `cuobjdump -sass` output, e.g. FFMA, HMMA, LDG. */
    fun sassOpcodes(cuobjdump: String): Set<String> = cuobjdump.lineSequence().mapNotNull(::sassOpcode).toSet()

    /** The opcode on one line of `cuobjdump -sass` output, if the line is an instruction. */
    fun sassOpcode(line: String): String? = SASS_LINE.find(line)?.groupValues?.get(1)

    fun checkSass(opcodes: Set<String>, policy: EffectivePolicy): List<String> =
        opcodes.filter { it in policy.forbiddenSass }.sorted().map { "device code uses forbidden instruction $it" }

    /**
     * `nm` output lines: "                 U cudaMalloc" / "0000000000000000 T _Z5solvePKfPfi". A line without an
     * address is a reference to a symbol defined elsewhere, whatever its type letter (U, w, v).
     */
    fun parseNm(nm: String): Pair<Set<String>, Set<String>> {
        val undefined = mutableSetOf<String>()
        val defined = mutableSetOf<String>()
        for (line in nm.lineSequence()) {
            val parts = line.trim().split(WHITESPACE)
            when {
                parts.size == 2 -> undefined += parts[1]
                parts.size == 3 && parts[1].length == 1 && parts[1][0] in GLOBAL_DEFINITIONS -> defined += parts[2]
            }
        }
        return undefined to defined
    }

    fun checkSymbols(undefined: Set<String>, defined: Set<String>, policy: EffectivePolicy): List<String> =
        undefined.filter { s -> policy.forbiddenUndefinedSymbols.any { it.matches(s) } }.sorted().map { "calls forbidden function $it" } +
            defined.filter { s -> policy.forbiddenDefinedSymbols.any { it.matches(s) } }.sorted().map { "defines reserved symbol $it" }

    private val WHITESPACE = Regex("\\s+")

    /** nm type letters of symbols other objects can see: upper case, plus `i` (ifunc) and `u` (unique global). */
    private const val GLOBAL_DEFINITIONS = "ABCDGRSTVWiu"
    private val INCLUDE_ARG = Regex("""([<"])([^<>"]+)[>"]\s*""")
    private val ASM = Regex("""(?<![\w$])(asm|__asm__|__asm)(?![\w$])""")
    private val SASS_LINE = Regex("""/\*[0-9a-f]{4,}\*/\s+(?:@!?U?P[T0-9]+\s+)?([A-Z][A-Z0-9_]*)""")

    private fun joinContinuations(s: String) = s.replace("\r\n", "\n").replace("\\\n", "")

    /** Removes // and /* */ comments, keeping string and char literals intact. */
    fun stripComments(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '/' && i + 1 < s.length && s[i + 1] == '/' -> {
                    while (i < s.length && s[i] != '\n') i++
                }
                c == '/' && i + 1 < s.length && s[i + 1] == '*' -> {
                    val end = s.indexOf("*/", i + 2)
                    val stop = if (end < 0) s.length else end + 2
                    for (k in i until stop) if (s[k] == '\n') out.append('\n')
                    out.append(' ')
                    i = stop
                }
                else -> {
                    val end = literalEndAt(s, i)
                    if (end == null) {
                        out.append(c)
                        i++
                    } else {
                        out.append(s, i, end)
                        i = end
                    }
                }
            }
        }
        return out.toString()
    }

    /** Replaces the contents of string/char literals with spaces (keeps newlines so line numbers survive). */
    fun stripStrings(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val end = literalEndAt(s, i)
            if (end == null) {
                out.append(s[i])
                i++
            } else {
                for (k in i until end) out.append(if (s[k] == '\n') '\n' else ' ')
                i = end
            }
        }
        return out.toString()
    }

    /**
     * End of the string, char or raw string literal starting at [i], or null if none starts there. The scan has to
     * agree with the compiler about where literals are: anything it wrongly takes for a literal or a comment
     * hides the `#include` and `asm` that follow from the checks.
     */
    private fun literalEndAt(s: String, i: Int): Int? = when {
        s[i] == '"' -> literalEnd(s, i)
        // in 1'000'000 the quote separates digits and opens nothing
        s[i] == '\'' -> if (isDigitSeparator(s, i)) null else literalEnd(s, i)
        isRawStringStart(s, i) -> rawLiteralEnd(s, i + 1)
        else -> null
    }

    private fun isDigitSeparator(s: String, quote: Int): Boolean {
        var start = quote
        while (start > 0 && (s[start - 1].isLetterOrDigit() || s[start - 1] == '_' || s[start - 1] == '\'' || s[start - 1] == '.')) start--
        return start < quote && s[start].isDigit()
    }

    /** `R"` with an optional encoding prefix (u8, u, U, L) that is not the tail of an identifier. */
    private fun isRawStringStart(s: String, i: Int): Boolean {
        if (s[i] != 'R' || i + 1 >= s.length || s[i + 1] != '"') return false
        val prefixStart = when {
            i >= 2 && s[i - 2] == 'u' && s[i - 1] == '8' -> i - 2
            i >= 1 && s[i - 1] in "uUL" -> i - 1
            else -> i
        }
        return prefixStart == 0 || !(s[prefixStart - 1].isLetterOrDigit() || s[prefixStart - 1] == '_')
    }

    private fun literalEnd(s: String, start: Int): Int {
        val q = s[start]
        var i = start + 1
        while (i < s.length && s[i] != q && s[i] != '\n') {
            if (s[i] == '\\') i++
            i++
        }
        return minOf(i + 1, s.length)
    }

    private fun rawLiteralEnd(s: String, quote: Int): Int {
        val open = s.indexOf('(', quote)
        if (open < 0) return s.length
        val delim = s.substring(quote + 1, open)
        val end = s.indexOf(")$delim\"", open)
        return if (end < 0) s.length else end + delim.length + 2
    }
}
