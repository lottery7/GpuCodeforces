package gpucodeforces.judge.api

/**
 * Integer expression over scalar parameters used for array sizes: `N`, `M*K`, `(N+255)/256*256`.
 * Supports `+ - * / %`, parentheses, unary minus, decimal literals and identifiers. Evaluated in Long
 * with overflow checks. Mirrored by `judge_problem/spec.py` on the Python side.
 */
class SizeExpr private constructor(val source: String, private val root: Node) {

    val identifiers: Set<String> by lazy { buildSet { collect(root, this) } }

    fun eval(vars: Map<String, Long>): Long = eval(root, vars)

    override fun toString() = source

    private sealed interface Node
    private data class Num(val value: Long) : Node
    private data class Var(val name: String) : Node
    private data class Neg(val arg: Node) : Node
    private data class Bin(val op: Char, val left: Node, val right: Node) : Node

    private fun collect(node: Node, out: MutableSet<String>) {
        when (node) {
            is Num -> Unit
            is Var -> out += node.name
            is Neg -> collect(node.arg, out)
            is Bin -> { collect(node.left, out); collect(node.right, out) }
        }
    }

    private fun eval(node: Node, vars: Map<String, Long>): Long = when (node) {
        is Num -> node.value
        is Var -> vars[node.name] ?: throw IllegalArgumentException("unknown variable '${node.name}' in size '$source'")
        is Neg -> Math.negateExact(eval(node.arg, vars))
        is Bin -> {
            val l = eval(node.left, vars)
            val r = eval(node.right, vars)
            when (node.op) {
                '+' -> Math.addExact(l, r)
                '-' -> Math.subtractExact(l, r)
                '*' -> Math.multiplyExact(l, r)
                '/' -> if (r == 0L) throw ArithmeticException("division by zero in size '$source'") else l / r
                '%' -> if (r == 0L) throw ArithmeticException("division by zero in size '$source'") else l % r
                else -> error("unreachable")
            }
        }
    }

    companion object {
        fun parse(source: String): SizeExpr = SizeExpr(source, Parser(source).parseAll())
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun parseAll(): Node {
            val node = expr()
            skipWs()
            if (pos != s.length) fail("unexpected '${s[pos]}'")
            return node
        }

        private fun expr(): Node {
            var left = term()
            while (true) {
                skipWs()
                val c = s.getOrNull(pos) ?: return left
                if (c != '+' && c != '-') return left
                pos++
                left = Bin(c, left, term())
            }
        }

        private fun term(): Node {
            var left = unary()
            while (true) {
                skipWs()
                val c = s.getOrNull(pos) ?: return left
                if (c != '*' && c != '/' && c != '%') return left
                pos++
                left = Bin(c, left, unary())
            }
        }

        private fun unary(): Node {
            skipWs()
            if (s.getOrNull(pos) == '-') {
                pos++
                return Neg(unary())
            }
            return primary()
        }

        private fun primary(): Node {
            skipWs()
            val c = s.getOrNull(pos) ?: fail("unexpected end")
            return when {
                c == '(' -> {
                    pos++
                    val inner = expr()
                    skipWs()
                    if (s.getOrNull(pos) != ')') fail("expected ')'")
                    pos++
                    inner
                }
                c.isDigit() -> {
                    val start = pos
                    while (pos < s.length && s[pos].isDigit()) pos++
                    Num(s.substring(start, pos).toLongOrNull() ?: fail("number too large"))
                }
                c == '_' || c.isLetter() -> {
                    val start = pos
                    while (pos < s.length && (s[pos] == '_' || s[pos].isLetterOrDigit())) pos++
                    Var(s.substring(start, pos))
                }
                else -> fail("unexpected '$c'")
            }
        }

        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun fail(msg: String): Nothing = throw IllegalArgumentException("invalid size expression '$s' at $pos: $msg")
    }
}
