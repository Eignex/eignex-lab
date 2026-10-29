package com.eignex.lab.checker

import java.math.BigInteger
import kotlinx.serialization.Serializable

/** A point check only. No search, propagation, lowering or Klause code is used here. */
@Serializable
internal data class WeightedEvaluation(val state: String, val reason: String, val exactObjective: String? = null,
                                       val variables: Int = 0, val constraints: Int = 0)

/** Independent implementation of PB Competition 2010 section 3 and MaxSAT Evaluation WCNF semantics. */
internal object WeightedSourceEvaluation {
    const val VERSION = "eignex-lab-weighted-point-1"
    const val MAX_VARIABLES = 1_000_000
    const val MAX_TOKEN = 4096
    const val MAX_BYTES = 32 * 1024 * 1024
    private val integer = Regex("[+-]?[0-9]+")
    private val variable = Regex("x[1-9][0-9]*")

    fun check(format: String, source: String, witness: String, objective: String?): WeightedEvaluation = try {
        if (source.length > MAX_BYTES || witness.length > MAX_BYTES) throw Unsupported("weighted input exceeds 32 MiB")
        val assignment = assignment(witness, format == "WCNF")
        val result = if (format == "WCNF") wcnf(source, assignment) else opb(source, assignment, format == "WBO")
        if (result.state != LabCheck.VALID) result
        else if (result.exactObjective == null) {
            if (objective != null) throw Unsupported("source has no objective for the claimed cost")
            result
        }
        else if (objective == null) WeightedEvaluation(LabCheck.NOT_RUN, "no exact objective was retained")
        else {
            if (objective.length > MAX_TOKEN || !integer.matches(objective)) throw Invalid("claimed objective is not an exact integer")
            if (BigInteger(objective) != BigInteger(result.exactObjective))
                result.copy(state = LabCheck.INVALID, reason = "claimed objective $objective differs from exact source cost ${result.exactObjective}")
            else result.copy(reason = "complete original point and exact objective ${result.exactObjective} accepted; no optimality check")
        }
    } catch (e: Invalid) { WeightedEvaluation(LabCheck.INVALID, e.message.orEmpty()) }
      catch (e: Unsupported) { WeightedEvaluation(LabCheck.UNSUPPORTED, e.message.orEmpty()) }

    private class Invalid(message: String) : RuntimeException(message)
    private class Unsupported(message: String) : RuntimeException(message)

    private fun id(text: String): Int = text.toIntOrNull()?.takeIf { it in 1..MAX_VARIABLES }
        ?: throw Unsupported("variable index outside 1..$MAX_VARIABLES")

    private fun assignment(witness: String, numeric: Boolean): Map<Int, Boolean> {
        if (numeric) {
            val bits = Regex("v\\s+([01]+)\\s*").matchEntire(witness.trim())?.groupValues?.get(1)
            if (bits != null) {
                if (bits.length > MAX_VARIABLES) throw Unsupported("bit witness exceeds variable limit")
                return bits.mapIndexed { index, bit -> index + 1 to (bit == '1') }.toMap()
            }
        }
        val tokens = Tokens(witness, comments = false)
        if (tokens.take() != "v") throw Invalid("expected a complete v assignment")
        val values = HashMap<Int, Boolean>()
        while (tokens.peek() != null) {
            val token = tokens.take()
            if (numeric && token == "0") {
                if (tokens.peek() != null) throw Invalid("tokens follow witness terminator")
                break
            }
            val positive = !token.startsWith('-')
            val name = token.removePrefix("-")
            val index = if (numeric) {
                if (!name.matches(Regex("[1-9][0-9]*"))) throw Invalid("malformed WCNF coordinate ${token.take(64)}")
                id(name)
            } else {
                if (!variable.matches(name)) throw Invalid("malformed PB coordinate ${token.take(64)}")
                id(name.substring(1))
            }
            if (values.put(index, positive) != null) throw Invalid("duplicate assignment for x$index")
        }
        // Both terminated DIMACS and the lab's unterminated final v line are accepted.
        return values
    }

    private class Point(private val values: Map<Int, Boolean>) {
        var max = 0
        fun literal(token: String, pb: Boolean): Boolean {
            val negative = token.startsWith(if (pb) "~" else "-")
            val name = token.removePrefix(if (pb) "~" else "-")
            if (pb && !variable.matches(name)) throw Unsupported("unsupported PB literal ${token.take(64)}")
            if (!pb && !name.matches(Regex("[1-9][0-9]*"))) throw Unsupported("unsupported WCNF literal ${token.take(64)}")
            val index = id(if (pb) name.substring(1) else name)
            max = maxOf(max, index)
            return (values[index] ?: throw Invalid("missing original coordinate x$index")) != negative
        }
        fun complete(declared: Int?) {
            val count = declared ?: max
            if (max > count) throw Unsupported("source variable exceeds declared variable count")
            if (values.size != count || values.keys.any { it > count }) throw Invalid("assignment must contain exactly original coordinates x1..x$count")
            for (i in 1..count) if (i !in values) throw Invalid("missing original coordinate x$i")
        }
    }

    private fun number(token: String): BigInteger {
        if (!integer.matches(token)) throw Unsupported("expected integer, found ${token.take(64)}")
        return BigInteger(token)
    }

    private fun wcnf(source: String, values: Map<Int, Boolean>): WeightedEvaluation {
        val t = Tokens(source, comments = true, comment = 'c')
        val p = Point(values)
        var declared: Int? = null
        var expectedRows: Int? = null
        var top: BigInteger? = null
        if (t.peek() == "p") {
            t.take()
            t.expect("wcnf")
            declared = t.count()
            if (declared > MAX_VARIABLES) throw Unsupported("WCNF variable count exceeds limit")
            expectedRows = t.count()
            // Older weighted (non-partial) WCNF may omit top on the header line.
            top = t.headerRemainder()?.let(::number)
            if (top != null && top.signum() <= 0) throw Unsupported("WCNF top must be positive")
        }
        var rows = 0
        var cost = BigInteger.ZERO
        var violation: String? = null
        while (t.peek() != null) {
            val weightToken = t.take()
            val weight = if (weightToken == "h") null else number(weightToken)
            if (weight != null && weight.signum() <= 0) throw Unsupported("WCNF weight must be positive")
            if (weight == null && declared != null) throw Unsupported("h clauses require headerless WCNF")
            val hard = weight == null || (top != null && weight >= top)
            var satisfied = false
            while (true) {
                val literal = t.take()
                if (literal == "0") break
                // Evaluate all literals, including those after an already satisfied literal.
                val truth = p.literal(literal, pb = false)
                satisfied = satisfied || truth
            }
            rows++
            if (!satisfied) {
                if (hard) violation = violation ?: "hard WCNF clause $rows is false"
                else cost += weight!!
            }
        }
        if (expectedRows != null && rows != expectedRows) throw Unsupported("WCNF clause count differs from header")
        p.complete(declared)
        return WeightedEvaluation(if (violation == null) LabCheck.VALID else LabCheck.INVALID,
            violation ?: "complete original point accepted", cost.toString(), declared ?: p.max, rows)
    }

    private fun opb(source: String, values: Map<Int, Boolean>, requireWbo: Boolean): WeightedEvaluation {
        val header = source.lineSequence().filter { it.trimStart().startsWith('*') }
            .mapNotNull { Regex("#variable=\\s*([0-9]+)\\s+#constraint=\\s*([0-9]+)").find(it) }.firstOrNull()
        val declared = header?.groupValues?.get(1)?.toIntOrNull()
            ?: if (header != null) throw Unsupported("PB variable count too large") else null
        if (declared != null && declared !in 0..MAX_VARIABLES) throw Unsupported("PB variable count exceeds $MAX_VARIABLES")
        val expectedRows = header?.groupValues?.get(2)?.toIntOrNull()
            ?: if (header != null) throw Unsupported("PB constraint count too large") else null
        val t = Tokens(source, comments = true)
        val p = Point(values)
        var top: BigInteger? = null
        val weighted = t.peek() == "soft"
        var objective: BigInteger? = null
        if (weighted) {
            t.take(); t.expect(":")
            if (t.peek() != ";") {
                top = number(t.take())
                if (top.signum() <= 0) throw Unsupported("WBO top must be positive")
            }
            t.expect(";")
            objective = BigInteger.ZERO
        } else if (requireWbo) throw Unsupported("WBO requires a soft header")
        else if (t.peek() == "min") {
            t.take(); t.expect(":")
            objective = sum(t, p, setOf(";"))
            t.expect(";")
        }
        var rows = 0
        var violation: String? = null
        while (t.peek() != null) {
            var weight: BigInteger? = null
            if (t.peek() == "[") {
                if (!weighted) throw Unsupported("soft constraint without WBO header")
                t.take(); weight = number(t.take()); t.expect("]")
                if (weight.signum() <= 0) throw Unsupported("WBO weight must be positive")
            }
            val lhs = sum(t, p, setOf(">=", "=", "<="))
            val relation = t.take()
            val rhs = number(t.take())
            t.expect(";")
            val satisfied = when (relation) {
                ">=" -> lhs >= rhs
                "=" -> lhs == rhs
                "<=" -> lhs <= rhs
                else -> throw Unsupported("unsupported PB relation $relation")
            }
            rows++
            if (!satisfied) {
                if (weight == null || (top != null && weight >= top)) violation = violation ?: "hard PB constraint $rows is false"
                else objective = objective!! + weight
            }
        }
        if (expectedRows != null && rows != expectedRows) throw Unsupported("PB constraint count differs from header")
        p.complete(declared)
        if (weighted && top != null && objective!! >= top) violation = violation ?: "WBO cost $objective is not strictly below top $top"
        return WeightedEvaluation(if (violation == null) LabCheck.VALID else LabCheck.INVALID,
            violation ?: "complete original point accepted", objective?.toString(), declared ?: p.max, rows)
    }

    private fun sum(t: Tokens, p: Point, stops: Set<String>): BigInteger {
        var result = BigInteger.ZERO
        while (t.peek() !in stops) {
            val coefficient = number(t.take())
            var product = true
            var literals = 0
            while (t.peek()?.let { it.startsWith('x') || it.startsWith("~x") } == true) {
                val truth = p.literal(t.take(), pb = true)
                product = product && truth
                literals++
            }
            if (literals == 0) throw Unsupported("PB term has no literal")
            if (product) result += coefficient
        }
        return result
    }

    /** Bounded tokens, preserving line boundaries for optional old WCNF top; no whole-line allocation. */
    private class Tokens(private val text: String, private val comments: Boolean, private val comment: Char = '*') {
        private var at = 0
        private var pending: String? = null
        private var lineStart = true
        fun peek(): String? {
            if (pending == null) pending = next()
            return pending
        }
        fun take(): String = peek()?.also { pending = null } ?: throw Unsupported("unexpected end of source or witness")
        fun expect(value: String) { if (take() != value) throw Unsupported("expected $value") }
        fun count(): Int = take().toIntOrNull()?.takeIf { it >= 0 } ?: throw Unsupported("invalid header count")
        fun headerRemainder(): String? {
            while (at < text.length && text[at] in " \t\r") at++
            if (at == text.length || text[at] == '\n') return null
            val value = take()
            while (at < text.length && text[at] in " \t\r") at++
            if (at < text.length && text[at] != '\n') throw Unsupported("unexpected WCNF header field")
            return value
        }
        private fun next(): String? {
            while (at < text.length) {
                val c = text[at]
                if (c.isWhitespace()) { if (c == '\n') lineStart = true; at++; continue }
                if (comments && lineStart && c == comment) {
                    while (at < text.length && text[at] != '\n') at++
                    continue
                }
                break
            }
            if (at == text.length) return null
            lineStart = false
            val start = at++
            val c = text[start]
            if (c in ":;[]=") return c.toString()
            if (c == '<' || c == '>') {
                if (at < text.length && text[at] == '=') return text.substring(start, ++at)
                throw Unsupported("strict PB relations are unsupported")
            }
            while (at < text.length && !text[at].isWhitespace() && text[at] !in ":;[]=<>") {
                if (at - start >= MAX_TOKEN) throw Unsupported("token exceeds $MAX_TOKEN characters")
                at++
            }
            return text.substring(start, at)
        }
    }
}
