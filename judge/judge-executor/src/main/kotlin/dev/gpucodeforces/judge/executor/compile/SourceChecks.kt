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

    /** Headers actually opened by the compiler (from the -MD dependency file). */
    fun checkDependencies(depFile: String, policy: EffectivePolicy): List<String> {
        val paths = parseDepFile(depFile)
        return paths.drop(1) // first entry is the source itself
            .mapNotNull { p -> policy.forbiddenPathFragments.firstOrNull { p.contains(it) }?.let { p } }
            .distinct()
            .map { "uses a forbidden library header: ${it.substringAfter("/include/")}" }
    }

    fun parseDepFile(text: String): List<String> =
        text.replace("\\\n", " ").substringAfter(':').split(Regex("\\s+")).filter { it.isNotBlank() && it != "\\" }

    /** Opcodes of `cuobjdump -sass` output, e.g. FFMA, HMMA, LDG. */
    fun sassOpcodes(cuobjdump: String): Set<String> = buildSet {
        for (line in cuobjdump.lineSequence()) {
            val m = SASS_LINE.find(line) ?: continue
            add(m.groupValues[1])
        }
    }

    fun checkSass(opcodes: Set<String>, policy: EffectivePolicy): List<String> =
        opcodes.filter { it in policy.forbiddenSass }.sorted().map { "device code uses forbidden instruction $it" }

    /** `nm` output lines: "                 U cudaMalloc" / "0000000000000000 T _Z5solvePKfPfi". */
    fun parseNm(nm: String): Pair<Set<String>, Set<String>> {
        val undefined = HashSet<String>()
        val defined = HashSet<String>()
        for (line in nm.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            when {
                parts.size == 2 && (parts[0] == "U" || parts[0] == "w") -> undefined += parts[1]
                parts.size == 3 && parts[1].length == 1 && parts[1][0].isUpperCase() && parts[1] != "U" -> defined += parts[2]
            }
        }
        return undefined to defined
    }

    fun checkSymbols(undefined: Set<String>, defined: Set<String>, policy: EffectivePolicy): List<String> = buildList {
        undefined.filter { s -> policy.forbiddenUndefinedSymbols.any { it.matches(s) } }.sorted()
            .forEach { add("calls forbidden function $it") }
        defined.filter { s -> policy.forbiddenDefinedSymbols.any { it.matches(s) } }.sorted()
            .forEach { add("defines reserved symbol $it") }
    }

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
                c == '"' || c == '\'' -> {
                    val end = literalEnd(s, i)
                    out.append(s, i, end)
                    i = end
                }
                c == 'R' && i + 1 < s.length && s[i + 1] == '"' && (i == 0 || !s[i - 1].isLetterOrDigit()) -> {
                    val end = rawLiteralEnd(s, i + 1)
                    out.append(s, i, end)
                    i = end
                }
                else -> {
                    out.append(c)
                    i++
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
            val c = s[i]
            val end = when {
                c == '"' || c == '\'' -> literalEnd(s, i)
                c == 'R' && i + 1 < s.length && s[i + 1] == '"' && (i == 0 || !s[i - 1].isLetterOrDigit()) -> rawLiteralEnd(s, i + 1)
                else -> -1
            }
            if (end < 0) {
                out.append(c)
                i++
            } else {
                for (k in i until end) out.append(if (s[k] == '\n') '\n' else ' ')
                i = end
            }
        }
        return out.toString()
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
