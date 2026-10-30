package com.eignex.lab.checker

import com.eignex.lab.domain.*
import com.eignex.lab.reference.*

/** Original CNF evaluation only: no solver, propagation, coordinate repair or Klause dependency. */
object DimacsSourceEvaluation {
    const val VERSION = "eignex-lab-dimacs-complete-point-1"
    const val MAX_WITNESS = 8 * 1024 * 1024
    data class Limits(
        val bytes: Int = WeightedSourceEvaluation.MAX_BYTES,
        val witness: Int = MAX_WITNESS,
        val variables: Int = 1_000_000,
        val clauses: Int = 2_000_000,
        val literals: Int = 8_000_000,
        val token: Int = 32,
    )

    fun check(source: String, witness: String, limits: Limits = Limits()): WeightedEvaluation = try {
        if (source.length > limits.bytes || witness.length > limits.witness) resource("source or witness size limit")
        val cnf = Tokens(source, comments = true, limits)
        val header = cnf.next() ?: unsupported("missing p cnf header")
        if (header.text != "p") unsupported("expected p cnf header")
        fun headerToken(): String {
            val token = cnf.next() ?: unsupported("incomplete p cnf header")
            if (token.line != header.line) unsupported("incomplete p cnf header")
            return token.text
        }
        if (headerToken() != "cnf") unsupported("only p cnf sources are covered")
        val variables = count(headerToken())
        val clauses = count(headerToken())
        if (variables > limits.variables || clauses > limits.clauses) resource("declared variable or clause limit")
        var token = cnf.next()
        if (token?.line == header.line) unsupported("extra p cnf header fields")
        val values = assignment(witness, variables, limits)
        var rows = 0
        var literals = 0
        var pending = false
        var satisfied = false
        var firstFalse: Int? = null
        while (token != null) {
            if (rows >= clauses) unsupported("clauses follow declared clause count")
            if (token.text == "0") {
                rows++
                if (!satisfied && firstFalse == null) firstFalse = rows
                pending = false
                satisfied = false
            } else {
                val literal = literal(token.text, source = true)
                val index = if (literal < 0) -literal else literal
                if (index > variables) unsupported("source literal outside original coordinates 1..$variables")
                if (++literals > limits.literals) resource("literal evaluation limit")
                // Evaluate every literal, even after this clause is already satisfied.
                val truth = values[index].toInt() == if (literal > 0) 1 else -1
                satisfied = satisfied || truth
                pending = true
            }
            token = cnf.next()
        }
        if (pending || rows != clauses) unsupported("unterminated clause or declared clause count mismatch")
        if (firstFalse != null) WeightedEvaluation(LabCheck.INVALID, "original CNF clause $firstFalse is false", variables = variables, constraints = rows)
        else WeightedEvaluation(LabCheck.VALID, "complete original coordinates and all $rows CNF clauses accepted; SAT point only, no UNSAT or optimality check", variables = variables, constraints = rows)
    } catch (e: Refusal) { WeightedEvaluation(e.state, e.message.orEmpty()) }

    private data class Token(val text: String, val line: Int)
    /** Bounded tokens with line provenance; comments are permitted only at the start of a source line. */
    private class Tokens(val text: String, val comments: Boolean, val limits: Limits) {
        private var at = 0
        private var line = 1
        private var start = true
        fun next(): Token? {
            while (at < text.length) {
                val c = text[at]
                if (c == '\n' || c == '\r') { at++; line++; start = true; continue }
                if (c == ' ' || c == '\t') { at++; continue }
                if (comments && start && c == 'c') {
                    while (at < text.length && text[at] != '\n' && text[at] != '\r') at++
                    continue
                }
                break
            }
            if (at == text.length) return null
            start = false
            val begin = at
            while (at < text.length && text[at] !in " \t\r\n") {
                if (at - begin >= limits.token) resource("numeric token length limit")
                at++
            }
            return Token(text.substring(begin, at), line)
        }
    }

    private fun assignment(witness: String, variables: Int, limits: Limits): ByteArray {
        val tokens = Tokens(witness, comments = false, limits)
        var previous = tokens.next() ?: invalid("missing complete v assignment")
        if (previous.text != "v") invalid("expected complete v assignment")
        val values = ByteArray(variables + 1)
        var count = 0
        var ended = false
        while (true) {
            val token = tokens.next() ?: break
            if (ended) invalid("tokens follow witness terminator")
            if (token.text == "v") {
                if (token.line == previous.line || previous.text == "v") invalid("v prefix must start a nonempty assignment line")
            } else if (token.text == "0") ended = true
            else {
                val literal = literal(token.text, source = false)
                val index = if (literal < 0) -literal else literal
                if (index > variables) invalid("witness coordinate outside original 1..$variables")
                val value: Byte = if (literal > 0) 1 else -1
                if (values[index] != 0.toByte()) {
                    if (values[index] != value) invalid("contradictory assignment for original coordinate $index")
                    invalid("duplicate assignment for original coordinate $index")
                }
                values[index] = value
                count++
            }
            previous = token
        }
        if (previous.text == "v" && variables != 0) invalid("empty final assignment line")
        // A lab final v line may omit the terminator, but it may never omit a declared coordinate.
        if (count != variables) invalid("complete assignment requires every original coordinate 1..$variables exactly once (received $count)")
        return values
    }

    private val countPattern = Regex("0|[1-9][0-9]*")
    private val literalPattern = Regex("-?[1-9][0-9]*")

    private fun count(token: String): Int {
        if (!countPattern.matches(token)) unsupported("malformed CNF header count")
        return token.toIntOrNull() ?: resource("CNF header count exceeds integer limit")
    }
    private fun literal(token: String, source: Boolean): Int {
        fun bad(): Nothing = if (source) unsupported("malformed CNF literal ${token.take(32)}") else invalid("malformed witness coordinate ${token.take(32)}")
        if (!literalPattern.matches(token)) bad()
        return token.toIntOrNull()?.takeUnless { it == Int.MIN_VALUE } ?: bad()
    }
    private class Refusal(val state: String, message: String) : RuntimeException(message)
    private fun invalid(message: String): Nothing = throw Refusal(LabCheck.INVALID, message)
    private fun unsupported(message: String): Nothing = throw Refusal(LabCheck.UNSUPPORTED, message)
    private fun resource(message: String): Nothing = unsupported("resource limit: $message")
}
