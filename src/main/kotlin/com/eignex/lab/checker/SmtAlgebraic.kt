package com.eignex.lab.checker

import com.eignex.lab.reference.CorpusFiles
import java.io.File

/** A Z3 installation the lab checks algebraic SMT-LIB models with: the executable, its version and its SHA-256. */
data class Z3Tool(val executable: File, val version: String, val sha256: String, val memoryMb: Int)

/**
 * Finds `z3` ([LAB_Z3], else on `PATH`), the one the lab's SMT-LIB reference runs use, once, with its memory cap
 * [LAB_Z3_MEMORY_MB] (default 2048); throws, saying why, when there is none.
 */
class Z3Install(private val env: (String) -> String? = System::getenv) {
    private val found by lazy { runCatching { locate() } }

    fun tool(): Z3Tool = found.getOrThrow()

    private fun locate(): Z3Tool {
        val requested = env("LAB_Z3")?.takeIf { it.isNotBlank() } ?: "z3"
        val executable = if (File(requested).isAbsolute || File(requested).parent != null) File(requested).takeIf { it.canExecute() }
        else env("PATH").orEmpty().split(File.pathSeparator).map { File(it, requested) }.firstOrNull { it.isFile && it.canExecute() }
        executable ?: error("z3 is not installed (not on PATH, LAB_Z3 unset or not executable: $requested)")
        val memoryMb = (env("LAB_Z3_MEMORY_MB") ?: "2048").toIntOrNull()?.takeIf { it > 0 } ?: error("LAB_Z3_MEMORY_MB is not a positive number")
        val dir = kotlin.io.path.createTempDirectory("z3-").toFile()
        val run = try {
            ToolProcess.run(listOf(executable.path, "--version"), dir, PROBE_MS)
        } finally {
            dir.deleteRecursively()
        }
        check(run.exit == 0) { "z3 --version failed (exit ${run.exit}): ${run.tail()}" }
        val version = Regex("version ([0-9][^\\s]*)").find(run.stdout)?.groupValues?.get(1) ?: error("z3 --version said no version: ${run.tail()}")
        return Z3Tool(executable, version, sha256(executable.toPath().toRealPath().toFile()), memoryMb)
    }

    private companion object {
        const val PROBE_MS = 10_000L
    }
}

/**
 * The fallback for SMT-LIB models with algebraic numbers, which Dolmen cannot read: Z3, an established SMT solver,
 * decides whether the original source holds with every declared constant pinned to the model's value. Klause writes an
 * irrational value as Z3 does, `(root-obj p i)`, the `i`-th real root of the univariate polynomial `p` in `x`, and Z3
 * reads that notation in its own input, so the values go to it as the model wrote them, unconverted.
 *
 * The one input transformation: the source's text up to its single `(check-sat)`, then `(assert (= name value))` for
 * each constant the source declares, with the value as the model wrote it, then `(check-sat)`. Nothing of the source
 * is rewritten. Z3's decision procedure for nonlinear real arithmetic (nlsat) then gives the verdict: `sat` is a valid
 * model, `unsat` an invalid one (the pinned values violate an assertion), and `unknown`, a timeout or any error (such
 * as a `root-obj` Z3 refuses, which it reports and then skips) undecided. A model that leaves a declared constant
 * undefined is undecided too, since an unpinned constant would let Z3 solve for it rather than check it. A source with
 * more than one `(check-sat)`, `push` or `pop`, or with uninterpreted functions, has no single model to pin and is
 * unsupported. Z3 alone decides: cvc5 does not read `root-obj`, and feeding it a value would take an isolating interval
 * the lab would have to compute itself.
 */
class Z3AlgebraicCheck(private val install: () -> Z3Tool, private val timeoutMs: Long = TIMEOUT_MS) {
    /** Z3's verdict on [case]'s model, which Dolmen could not decide ([dolmen]), in scratch directory [work]. */
    fun check(case: CheckCase, work: File, dolmen: Verdict): Verdict {
        val tool = install()
        val policy = policy(tool.version)
        var receipts = dolmen.resourceReceipts
        var inputs = dolmen.inputs
        fun verdict(state: String, reason: String, diagnostic: String) =
            Verdict(state, reason, tool.version, tool.sha256, policy, CHECKER,
                "dolmen: ${dolmen.state}: ${dolmen.reason}\n$diagnostic", receipts, inputs)
        val pinned = try {
            when (val got = pin(CorpusFiles.readText(case.sources.model), case.witness)) {
                is Pinned.Refused -> return verdict(got.state, got.reason, "no Z3 run")
                is Pinned.Input -> got
            }
        } catch (e: IllegalArgumentException) {
            return verdict(LabCheck.UNKNOWN, "the source or model is not readable SMT-LIB: ${e.message}", "no Z3 run")
        }
        val input = File(work, "pinned.smt2").apply { writeText(pinned.text) }
        inputs = inputs + CheckedInput(input.canonicalPath, sha256(input), pinned.text)
        val seconds = ((timeoutMs - GRACE_MS) / 1000).coerceAtLeast(1)
        val command = listOf(tool.executable.path, "-T:$seconds", "-memory:${tool.memoryMb}", "-smt2", input.name)
        val run = ToolProcess.run(command, work, timeoutMs,
            limits = ToolLimits(((timeoutMs - GRACE_MS) / 1000).coerceAtLeast(1), timeoutMs), inputFiles = listOf(input))
        receipts = receipts + listOfNotNull(run.resources)
        val lines = run.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val error = lines.firstOrNull { it.startsWith("(error") }
        val answer = lines.lastOrNull { it in ANSWERS }
        val diagnostic = "command: ${command.joinToString(" ")} (in a scratch directory)\n" +
            "pinned.smt2: the source's first ${pinned.prefixBytes} bytes, up to its (check-sat), then:\n${pinned.pins.take(PINS_CAP)}" +
            (if (pinned.pins.length > PINS_CAP) "…\n" else "") + "(check-sat)\n" +
            "exit ${run.exit ?: "none (killed)"} after ${run.elapsedMs} ms; output: ${run.tail(DIAGNOSTIC_LINES)}"
        return when {
            run.timedOut -> verdict(LabCheck.UNKNOWN, "Z3 did not finish within ${timeoutMs / 1000} s", diagnostic)
            run.resources?.qualified() != true -> verdict(LabCheck.UNKNOWN, "Z3's resource envelope was incomplete or exceeded", diagnostic)
            error != null -> verdict(LabCheck.UNKNOWN, "Z3 could not read the pinned source: $error", diagnostic)
            run.exit != 0 || run.stderr.isNotBlank() -> verdict(LabCheck.UNKNOWN, "Z3 did not exit cleanly: ${run.tail()}", diagnostic)
            answer == "sat" -> verdict(LabCheck.VALID, "Z3 found the source satisfied with every constant pinned to the model", diagnostic)
            answer == "unsat" -> verdict(LabCheck.INVALID, "Z3 found the source unsatisfiable with every constant pinned to the model", diagnostic)
            answer != null -> verdict(LabCheck.UNKNOWN, "Z3 could not decide the pinned source ($answer)", diagnostic)
            else -> verdict(LabCheck.UNKNOWN, "Z3 gave no answer (exit ${run.exit}): ${run.tail()}", diagnostic)
        }
    }

    /** The pinned input for [source] and [witness], or why there is none. */
    internal sealed interface Pinned {
        data class Input(val text: String, val prefixBytes: Int, val pins: String) : Pinned
        data class Refused(val state: String, val reason: String) : Pinned
    }

    companion object {
        /** Z3's own `-T`, this less [GRACE_MS], and room to start and stop. */
        const val TIMEOUT_MS = 35_000L
        private const val GRACE_MS = 5_000L
        const val CHECKER = "z3"
        private const val PINS_CAP = 1_000
        private const val DIAGNOSTIC_LINES = 4
        private val ANSWERS = setOf("sat", "unsat", "unknown", "timeout")

        /** The policy for verdicts from Z3 [version]: a new Z3 is a new policy. */
        fun policy(version: String) = "lab-smtlib-z3-algebraic-$version-v2"

        /** Whether a model has an algebraic number, Z3's `root-obj`, which Dolmen cannot read. */
        fun algebraic(witness: String): Boolean = ROOT_OBJ.containsMatchIn(witness)

        private val ROOT_OBJ = Regex("\\(\\s*root-obj[\\s()]")

        /**
         * [source] cut before its one `(check-sat)`, then an assertion pinning each constant it declares to its value in
         * [witness]'s last `( … )` block of `(define-fun name () Sort value)` entries, then `(check-sat)`. Throws
         * [IllegalArgumentException] for text that is not balanced SMT-LIB.
         */
        internal fun pin(source: String, witness: String): Pinned {
            val commands = SmtText.parse(source)
            fun head(e: SmtText.Expr) = ((e as? SmtText.Group)?.items?.firstOrNull() as? SmtText.Atom)?.text
            val checks = commands.filter { head(it) in setOf("check-sat", "check-sat-assuming") }
            if (checks.size != 1 || commands.any { head(it) in setOf("push", "pop") }) {
                return Pinned.Refused(LabCheck.UNSUPPORTED, "an incremental source has no single model to pin (${checks.size} check-sat)")
            }
            val declared = mutableListOf<String>()
            for (command in commands.takeWhile { it !== checks.single() }) {
                val items = (command as? SmtText.Group)?.items ?: continue
                val name = (items.getOrNull(1) as? SmtText.Atom)?.text ?: continue
                when (head(command)) {
                    "declare-const" -> declared += name
                    "declare-fun" -> if ((items.getOrNull(2) as? SmtText.Group)?.items.isNullOrEmpty()) declared += name
                        else return Pinned.Refused(LabCheck.UNSUPPORTED, "the source declares a function with arguments, $name")
                }
            }
            val model = SmtText.parse(witness).lastOrNull() as? SmtText.Group
                ?: return Pinned.Refused(LabCheck.UNKNOWN, "the witness has no ( … ) model block")
            val values = mutableMapOf<String, String>()
            for (entry in model.items) {
                val items = (entry as? SmtText.Group)?.items
                val name = (items?.getOrNull(1) as? SmtText.Atom)?.text
                if (items?.size != 5 || head(entry) != "define-fun" || name == null || (items[2] as? SmtText.Group)?.items?.isNotEmpty() != false) {
                    return Pinned.Refused(LabCheck.UNKNOWN, "a model entry is not a constant definition: ${witness.substring(entry.start, entry.end).take(120)}")
                }
                values[SmtText.symbol(name)] = witness.substring(items[4].start, items[4].end)
            }
            val missing = declared.filter { SmtText.symbol(it) !in values }
            if (missing.isNotEmpty()) {
                return Pinned.Refused(LabCheck.UNKNOWN, "the model assigns no value to ${missing.take(5).joinToString(", ")}" +
                    if (missing.size > 5) " and ${missing.size - 5} more" else "")
            }
            val pins = declared.joinToString("") { "(assert (= $it ${values.getValue(SmtText.symbol(it))}))\n" }
            val prefix = source.substring(0, checks.single().start)
            return Pinned.Input("$prefix\n$pins(check-sat)\n(exit)\n", prefix.toByteArray().size, pins)
        }
    }
}

/**
 * Just enough of SMT-LIB's lexical syntax to find a script's top-level commands and a model's entries, with each
 * expression's place in the text: parentheses, `;` comments, `"…"` strings (`""` inside one is a quote) and `|…|`
 * quoted symbols. It reads structure only and evaluates nothing.
 */
internal object SmtText {
    sealed interface Expr { val start: Int; val end: Int }
    data class Atom(val text: String, override val start: Int, override val end: Int) : Expr
    data class Group(val items: List<Expr>, override val start: Int, override val end: Int) : Expr

    /** [text]'s top-level expressions; throws [IllegalArgumentException] when its parentheses, strings or quoted
     *  symbols do not close. */
    fun parse(text: String): List<Expr> {
        val stack = ArrayDeque<Pair<Int, MutableList<Expr>>>()
        val top = mutableListOf<Expr>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == ';' -> while (i < text.length && text[i] != '\n') i++
                c == '(' -> { stack.addLast(i to mutableListOf()); i++ }
                c == ')' -> {
                    val (start, items) = stack.removeLastOrNull() ?: throw IllegalArgumentException("unmatched ) at $i")
                    val group = Group(items, start, i + 1)
                    (stack.lastOrNull()?.second ?: top) += group
                    i++
                }
                else -> {
                    val start = i
                    i = when (c) {
                        '"' -> {
                            var j = i + 1
                            while (true) {
                                require(j < text.length) { "unterminated string at $start" }
                                if (text[j] == '"') { if (j + 1 < text.length && text[j + 1] == '"') j += 2 else break } else j++
                            }
                            j + 1
                        }
                        '|' -> text.indexOf('|', i + 1).also { require(it >= 0) { "unterminated quoted symbol at $start" } } + 1
                        else -> {
                            var j = i
                            while (j < text.length && !text[j].isWhitespace() && text[j] !in "()\";|") j++
                            j
                        }
                    }
                    (stack.lastOrNull()?.second ?: top) += Atom(text.substring(start, i), start, i)
                }
            }
        }
        require(stack.isEmpty()) { "unclosed ( at ${stack.last().first}" }
        return top
    }

    /** A symbol as SMT-LIB identifies it: `|x|` and `x` are the same symbol. */
    fun symbol(text: String): String = if (text.length >= 2 && text.startsWith('|') && text.endsWith('|')) text.substring(1, text.length - 1) else text
}
