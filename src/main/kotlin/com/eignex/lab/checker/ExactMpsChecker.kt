package com.eignex.lab.checker

import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger

/** A narrow MPS point evaluator, independent of Klause's parser, model and acceptance code. */
class ExactMpsChecker(
    private val timeoutMs: Long = 120_000,
    private val nanoTime: () -> Long = System::nanoTime,
) : FormatChecker {
    override val format = "MPS"
    override val policy = Policies.MPS_EXACT
    override val checker = "Eignex Lab independent rational MPS point checker"

    override fun check(case: CheckCase, work: File): Verdict {
        val budget = MpsCheckBudget(timeoutMs, nanoTime)
        val sourceIdentity = javaClass.getResourceAsStream("/checker-source/com/eignex/lab/checker/ExactMpsChecker.kt")
            ?.use { sha256(it.readBytes()) }
        val location = File(javaClass.protectionDomain.codeSource.location.toURI())
        val binaryHash = location.takeIf { it.isFile }?.let(::sha256)
        fun done(state: String, reason: String) = Verdict(state, reason, "1 (source sha256 $sourceIdentity)", binaryHash,
            diagnostic = "exact rational arithmetic; zero tolerance; feasible point only; ${budget.operations} operations; " +
                "source <= $MAX_SOURCE bytes, witness <= $MAX_WITNESS chars, terms <= $MAX_TERMS, scalars <= ${MpsQ.MAX_BITS} bits; " +
                "deadline ${timeoutMs}ms; parser failures=unknown, unsupported/resource caps=unsupported, timeout=not-run")
        return try {
            val expected = (case.record["sourceHashes"] as? kotlinx.serialization.json.JsonObject)?.get("model")
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?: throw MpsCheckFailure(LabCheck.NOT_RUN, "source identity: missing original model SHA-256")
            budget.tick()
            val bytes = case.sources.model.inputStream().use { raw ->
                val input = if (case.sources.model.name.endsWith(".zst")) ZstdInputStreamNoFinalizer(raw).setLongMax(26) else raw
                input.use {
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        budget.tick()
                        val n = it.read(buffer)
                        if (n < 0) break
                        if (output.size() + n > MAX_SOURCE) unsupported("source byte limit")
                        output.write(buffer, 0, n)
                    }
                    output.toByteArray()
                }
            }
            if (sha256(bytes) != expected) throw MpsCheckFailure(LabCheck.NOT_RUN, "source identity: original model hash differs")
            val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            val point = ExactMpsPoint(budget)
            point.read(text)
            val objective = case.claimedObjective ?: return done(LabCheck.UNKNOWN, "incomplete claim: no exact objective")
            point.check(case.witness, objective)
            done(LabCheck.VALID, "exact feasible point accepted; ${point.sense} objective ${point.objective}; no optimality or bound checked")
        } catch (e: MpsCheckFailure) {
            done(e.state, e.message.orEmpty())
        } catch (e: InterruptedException) {
            throw e
        } catch (e: java.nio.charset.CharacterCodingException) {
            done(LabCheck.UNKNOWN, "parser failure: source is not UTF-8")
        }
    }

    companion object {
        const val MAX_SOURCE = 16 * 1024 * 1024
        const val MAX_WITNESS = 8 * 1024 * 1024
        const val MAX_TERMS = 200_000
        internal fun unsupported(why: String): Nothing = throw MpsCheckFailure(LabCheck.UNSUPPORTED, "unsupported: $why")
    }
}

/** Fractions go straight to exact checking. Other legacy coverage is retained under its own policy. */
class RationalMpsChecker(private val legacy: FormatChecker, private val exact: FormatChecker = ExactMpsChecker()) : FormatChecker {
    override val format = "MPS"
    override val policy = Policies.MPS_EXACT
    override val checker = "MPS source checker router"
    override fun check(case: CheckCase, work: File): Verdict {
        fun run(adapter: FormatChecker) = adapter.check(case, work).let {
            it.copy(policy = it.policy ?: adapter.policy, checker = it.checker ?: adapter.checker)
        }
        if ('/' in case.witness || '/' in case.claimedObjective.orEmpty()) return run(exact)
        val previous = run(legacy)
        return if (previous.state == LabCheck.UNSUPPORTED) run(exact) else previous
    }
}

private class MpsCheckFailure(val state: String, why: String) : RuntimeException(why)
private fun syntax(why: String): Nothing = throw MpsCheckFailure(LabCheck.UNKNOWN, "parser failure: $why")
private fun invalid(why: String): Nothing = throw MpsCheckFailure(LabCheck.INVALID, "invalid assignment: $why")

private class MpsCheckBudget(timeoutMs: Long, private val clock: () -> Long) {
    private val started = clock()
    private val timeout = timeoutMs.coerceIn(0, 120_000) * 1_000_000
    var operations = 0L
        private set
    fun tick() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("MPS check cancelled")
        if (clock() - started >= timeout) throw MpsCheckFailure(LabCheck.NOT_RUN, "timeout: exact MPS checker deadline")
        if (++operations > 2_000_000) ExactMpsChecker.unsupported("operation limit")
    }
}

/** Normalized rationals. Every operand and intermediate is bounded; no floating conversion or rounding. */
private data class MpsQ private constructor(val n: BigInteger, val d: BigInteger) : Comparable<MpsQ> {
    operator fun plus(b: MpsQ) = of(n * b.d + b.n * d, d * b.d)
    operator fun unaryMinus() = of(-n, d)
    operator fun minus(b: MpsQ) = this + -b
    operator fun times(b: MpsQ) = of(n * b.n, d * b.d)
    fun abs() = if (n.signum() < 0) -this else this
    override fun compareTo(other: MpsQ) = (n * other.d).compareTo(other.n * d)
    override fun toString() = if (d == BigInteger.ONE) "$n" else "$n/$d"
    companion object {
        const val MAX_BITS = 16_384
        val ZERO = of(BigInteger.ZERO, BigInteger.ONE)
        val ONE = of(BigInteger.ONE, BigInteger.ONE)
        private val decimal = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eEdD][+-]?[0-9]+)?")
        private val fraction = Regex("[+-]?[0-9]+/[0-9]+")
        fun of(n: BigInteger, d: BigInteger): MpsQ {
            if (n.bitLength() > MAX_BITS || d.bitLength() > MAX_BITS) ExactMpsChecker.unsupported("rational scalar bit limit")
            if (d.signum() <= 0) syntax("nonpositive denominator")
            val gcd = n.gcd(d)
            return MpsQ(n / gcd, d / gcd)
        }
        fun read(text: String): MpsQ {
            if (text.length > 4095) ExactMpsChecker.unsupported("number character limit")
            if (fraction.matches(text)) {
                val (n, d) = text.split('/')
                return of(BigInteger(n), BigInteger(d))
            }
            if (!decimal.matches(text)) syntax("invalid number ${text.take(64)}")
            val value = try { BigDecimal(text.replace('D', 'E').replace('d', 'e')) }
                catch (_: NumberFormatException) { ExactMpsChecker.unsupported("decimal exponent") }
            if (value.scale() !in -4095..4095) ExactMpsChecker.unsupported("decimal scale limit")
            return if (value.scale() < 0) of(value.unscaledValue() * BigInteger.TEN.pow(-value.scale()), BigInteger.ONE)
                else of(value.unscaledValue(), BigInteger.TEN.pow(value.scale()))
        }
    }
}

private class ExactMpsPoint(private val budget: MpsCheckBudget) {
    private data class Row(val type: String, val terms: MutableMap<String, MpsQ> = linkedMapOf(),
                           var rhs: MpsQ = MpsQ.ZERO, var range: MpsQ? = null)
    private data class Column(var integer: Boolean, var lower: MpsQ? = MpsQ.ZERO, var upper: MpsQ? = null,
                              var bounded: Boolean = false)
    private val rows = linkedMapOf<String, Row>()
    private val columns = linkedMapOf<String, Column>()
    private var objectiveRow: String? = null
    private var senseRead = false
    var sense = "MIN"
        private set
    var objective = MpsQ.ZERO
        private set

    fun read(source: String) {
        var section = ""
        val seen = hashSetOf<String>()
        val vectors = hashMapOf<String, String>()
        var integer = false
        var ended = false
        var terms = 0
        fun vector(name: String) {
            val first = vectors.getOrPut(section) { name }
            if (name != first) ExactMpsChecker.unsupported("multiple $section vectors")
        }
        for ((lineNumber, raw) in source.lineSequence().withIndex()) {
            budget.tick()
            if (raw.length > 8190) ExactMpsChecker.unsupported("MPS line limit at ${lineNumber + 1}")
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('*')) continue
            if (ended) syntax("data after ENDATA")
            val t = trimmed.split(Regex("\\s+"))
            val header = !raw.first().isWhitespace()
            if (header) {
                val next = t[0]
                if (next !in setOf("NAME", "OBJSENSE", "ROWS", "COLUMNS", "RHS", "RANGES", "BOUNDS", "ENDATA"))
                    ExactMpsChecker.unsupported("MPS section $next")
                if (!seen.add(next)) syntax("repeated section $next")
                if (next != "NAME" && "NAME" !in seen) syntax("missing NAME")
                val order = listOf("NAME", "OBJSENSE", "ROWS", "COLUMNS", "RHS", "RANGES", "BOUNDS", "ENDATA")
                if (section.isNotEmpty() && order.indexOf(next) <= order.indexOf(section)) syntax("section order $next")
                if (section == "OBJSENSE" && !senseRead) syntax("missing OBJSENSE value")
                section = next
                // NAME carries a label and may include descriptive metadata (for example Afiro SIZE notes).
                if (section == "NAME") continue
                if (section == "OBJSENSE" && t.size == 2) { readSense(t[1]); continue }
                if (t.size != 1) syntax("section header fields at ${lineNumber + 1}")
                if (section == "ENDATA") ended = true
                continue
            }
            when (section) {
                "OBJSENSE" -> { if (t.size != 1) syntax("OBJSENSE fields"); readSense(t[0]) }
                "ROWS" -> {
                    if (t.size != 2 || t[0] !in setOf("N", "E", "L", "G")) syntax("ROWS at ${lineNumber + 1}")
                    if (rows.putIfAbsent(t[1], Row(t[0])) != null) syntax("duplicate row ${t[1]}")
                    if (rows.size > 50_000) ExactMpsChecker.unsupported("row limit")
                    if (t[0] == "N" && objectiveRow == null) objectiveRow = t[1]
                }
                "COLUMNS" -> {
                    if (t.size == 3 && t[1] == "'MARKER'") {
                        when (t[2]) {
                            "'INTORG'" -> { if (integer) syntax("nested INTORG"); integer = true }
                            "'INTEND'" -> { if (!integer) syntax("unpaired INTEND"); integer = false }
                            else -> syntax("integer marker")
                        }
                        continue
                    }
                    if (t.size !in setOf(3, 5)) syntax("COLUMNS fields at ${lineNumber + 1}")
                    val col = columns.getOrPut(t[0]) { Column(integer) }
                    if (col.integer != integer) syntax("column crosses integer markers")
                    if (columns.size > 50_000) ExactMpsChecker.unsupported("column limit")
                    for (i in 1 until t.size step 2) {
                        budget.tick()
                        if (++terms > ExactMpsChecker.MAX_TERMS) ExactMpsChecker.unsupported("term limit")
                        val row = rows[t[i]] ?: syntax("unknown row ${t[i]}")
                        row.terms[t[0]] = (row.terms[t[0]] ?: MpsQ.ZERO) + MpsQ.read(t[i + 1])
                    }
                }
                "RHS", "RANGES" -> {
                    if (t.size !in setOf(3, 5)) syntax("$section fields")
                    vector(t[0])
                    for (i in 1 until t.size step 2) {
                        val row = rows[t[i]] ?: syntax("unknown row ${t[i]}")
                        val value = MpsQ.read(t[i + 1])
                        if (section == "RHS") row.rhs = value else {
                            if (row.type == "N") ExactMpsChecker.unsupported("range on free row")
                            row.range = value
                        }
                    }
                }
                "BOUNDS" -> {
                    if (t.size !in 3..4) syntax("BOUNDS fields")
                    vector(t[1])
                    val type = t[0]
                    if (type !in setOf("LO", "UP", "FX", "FR", "MI", "PL", "BV", "LI", "UI"))
                        ExactMpsChecker.unsupported("bound type $type")
                    val valued = type in setOf("LO", "UP", "FX", "LI", "UI")
                    if (valued != (t.size == 4)) syntax("bound value for $type")
                    val col = columns[t[2]] ?: syntax("unknown bound column ${t[2]}")
                    col.bounded = true
                    val v = if (valued) MpsQ.read(t[3]) else MpsQ.ZERO
                    when (type) {
                        "LO", "LI" -> col.lower = v
                        "UP", "UI" -> col.upper = v
                        "FX" -> { col.lower = v; col.upper = v }
                        "FR" -> { col.lower = null; col.upper = null }
                        "MI" -> col.lower = null
                        "PL" -> col.upper = null
                        "BV" -> { col.lower = MpsQ.ZERO; col.upper = MpsQ.ONE }
                    }
                    if (type in setOf("BV", "LI", "UI")) col.integer = true
                }
                else -> syntax("unexpected data at ${lineNumber + 1}")
            }
        }
        if (!ended || "ROWS" !in seen || "COLUMNS" !in seen || objectiveRow == null || integer) syntax("incomplete MPS sections/markers")
        for (col in columns.values) if (col.integer && !col.bounded) col.upper = MpsQ.ONE
    }

    private fun readSense(s: String) {
        if (senseRead) syntax("repeated OBJSENSE value")
        senseRead = true
        sense = when (s.uppercase()) {
            "MIN", "MINIMIZE" -> "MIN"
            "MAX", "MAXIMIZE" -> "MAX"
            else -> syntax("OBJSENSE $s")
        }
    }

    fun check(witness: String, claimed: String) {
        if (witness.length > ExactMpsChecker.MAX_WITNESS) ExactMpsChecker.unsupported("witness size limit")
        val tokens = witness.trim().splitToSequence(Regex("\\s+"))
        if (tokens.firstOrNull() != "v") syntax("witness must be v name=value")
        val values = hashMapOf<String, MpsQ>()
        for (token in tokens.drop(1)) {
            budget.tick()
            val at = token.lastIndexOf('=')
            if (at <= 0 || at == token.lastIndex) syntax("malformed witness coordinate")
            val name = token.substring(0, at)
            if (name !in columns) invalid("unknown coordinate ${name.take(64)}")
            if (values.putIfAbsent(name, MpsQ.read(token.substring(at + 1))) != null) syntax("duplicate witness coordinate")
        }
        budget.tick()
        if (values.size != columns.size) throw MpsCheckFailure(LabCheck.UNKNOWN, "incomplete witness: missing source coordinates")
        for ((name, col) in columns) {
            budget.tick()
            val x = values.getValue(name)
            if (col.integer && x.d != BigInteger.ONE) invalid("integrality of $name")
            if (col.lower?.let { x < it } == true || col.upper?.let { x > it } == true) invalid("bound of $name")
        }
        for ((name, row) in rows) {
            budget.tick()
            var activity = MpsQ.ZERO
            for ((column, coefficient) in row.terms) { budget.tick(); activity += coefficient * values.getValue(column) }
            if (name == objectiveRow) { objective = activity - row.rhs; continue }
            val r = row.range
            val lower = when (row.type) {
                "G" -> row.rhs
                "E" -> if (r != null && r < MpsQ.ZERO) row.rhs + r else row.rhs
                "L" -> r?.let { row.rhs - it.abs() }
                else -> null
            }
            val upper = when (row.type) {
                "L" -> row.rhs
                "E" -> if (r != null && r > MpsQ.ZERO) row.rhs + r else row.rhs
                "G" -> r?.let { row.rhs + it.abs() }
                else -> null
            }
            if (lower?.let { activity < it } == true || upper?.let { activity > it } == true) invalid("row $name")
        }
        budget.tick()
        if (objective != MpsQ.read(claimed)) invalid("$sense objective differs: reconstructed $objective")
    }
}
