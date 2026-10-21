package com.eignex.lab.checker

import com.eignex.lab.reference.CorpusFiles
import org.xcsp.parser.callbacks.SolutionChecker
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.PrintStream

/**
 * XCSP3 witnesses, checked by the official XCSP3 solution checker (`org.xcsp:xcsp3-tools` 2.5, MIT,
 * `org.xcsp.parser.callbacks.SolutionChecker`), whose verdict is taken as it is. It runs in a child JVM on the lab's
 * own classpath ([Xcsp3CheckMain]), with a heap cap and a time limit, so a checker that hangs or runs out of memory
 * costs this check and nothing else. An optimisation claim's instantiation carries the claimed objective as its
 * `cost`, which the checker compares with the objective it computes; a lexicographic objective the checker does not
 * manage is unsupported, in the checker's own words.
 */
class Xcsp3Checker(
    private val java: String = File(System.getProperty("java.home"), "bin/java").path,
    private val classpath: String = System.getProperty("java.class.path"),
    private val timeoutMs: Long = TIMEOUT_MS,
    private val heap: String = HEAP,
) : FormatChecker {
    override val format = "XCSP3"
    override val policy = Policies.XCSP3
    override val checker = "xcsp3-tools SolutionChecker"

    override fun check(case: CheckCase, work: File): Verdict {
        val instantiation = case.witness.removePrefix("v ").trim()
        val cost = if (case.optimize) {
            case.claimedObjective ?: return Verdict(LabCheck.UNKNOWN, "the record holds no claimed objective to check", VERSION, jarSha)
        } else null
        val witness = File(work, "witness.xml").apply { writeText(withCost(instantiation, cost)) }
        val run = CorpusFiles.withPlainFile(case.sources.model) { instance ->
            ToolProcess.run(
                listOf(java, "-Xmx$heap", "-XX:+UseSerialGC", "-cp", classpath, Xcsp3CheckMain::class.java.name, instance.absolutePath, witness.absolutePath),
                work, timeoutMs,
            )
        }
        if (run.timedOut) return Verdict(LabCheck.NOT_RUN, "the XCSP3 checker did not finish within ${timeoutMs / 1000} s", VERSION, jarSha)
        val result = run.stdout.lineSequence().lastOrNull { it.startsWith(MARKER) }?.split('\t')
            ?: return Verdict(LabCheck.NOT_RUN, "the XCSP3 checker ended without a verdict (exit ${run.exit}): ${run.tail()}", VERSION, jarSha)
        return Verdict(result.getOrElse(1) { LabCheck.UNKNOWN }, result.getOrElse(2) { "" }, VERSION, jarSha)
    }

    companion object {
        const val VERSION = "2.5"
        const val TIMEOUT_MS = 60_000L
        const val HEAP = "2g"
        internal const val MARKER = "LAB-CHECK\t"

        /** SHA-256 of the xcsp3-tools jar the lab runs. */
        val jarSha: String? by lazy {
            runCatching { File(SolutionChecker::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull()
                ?.takeIf { it.isFile }?.let(::sha256)
        }

        private val COST = Regex("""\s+cost\s*=\s*("[^"]*"|'[^']*')""")

        /** [instantiation] with its `cost` attribute set to [cost], or without one when null. */
        fun withCost(instantiation: String, cost: String?): String {
            val open = Regex("<instantiation\\b[^>]*>").find(instantiation) ?: return instantiation
            val tag = open.value.replace(COST, "")
            val costed = if (cost == null) tag else tag.replaceFirst("<instantiation", "<instantiation cost=\"$cost\"")
            return instantiation.replaceRange(open.range, costed)
        }
    }
}

/**
 * The child JVM an XCSP3 check runs in: `<instance> <instantiation>`, printing the verdict as one
 * `LAB-CHECK\t<state>\t<reason>` line. The checker's own output is kept in memory to quote a fatal error it reports.
 */
object Xcsp3CheckMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val real = System.out
        val captured = Bounded(CAPTURE_BYTES)
        System.setOut(PrintStream(captured, true))
        System.setErr(PrintStream(captured, true))
        val (state, reason) = try {
            val checker = FileInputStream(args[1]).use { SolutionChecker(false, args[0], it) }
            val violated = checker.violatedCtrs.orEmpty()
            val objectives = checker.invalidObjs.orEmpty()
            if (violated.isEmpty() && objectives.isEmpty()) {
                LabCheck.VALID to "the XCSP3 solution checker accepted the instantiation"
            } else {
                LabCheck.INVALID to buildList {
                    if (violated.isNotEmpty()) add("${violated.size} violated constraints: ${violated.take(3).joinToString("; ") { it.trim() }}")
                    if (objectives.isNotEmpty()) add("invalid objective: ${objectives.take(2).joinToString("; ") { it.trim() }}")
                }.joinToString("; ")
            }
        } catch (t: Throwable) {
            val fatal = captured.toString().lineSequence().map { it.trim() }.firstOrNull { it.startsWith("Fatal Error") }
            val why = fatal ?: (t.message ?: t::class.java.name)
            if ("cost for each objective" in why) LabCheck.UNSUPPORTED to "lexicographic objectives are not managed by the XCSP3 checker: $why"
            else LabCheck.UNKNOWN to "the XCSP3 checker could not decide: $why"
        }
        real.println(Xcsp3Checker.MARKER + state + "\t" + reason.replace('\t', ' ').replace('\n', ' ').take(REASON_CAP))
        real.flush()
        System.exit(0)
    }

    private const val CAPTURE_BYTES = 64 * 1024

    /** The first [cap] bytes written to it; the rest is dropped. */
    private class Bounded(private val cap: Int) : ByteArrayOutputStream() {
        @Synchronized override fun write(b: Int) { if (size() < cap) super.write(b) }
        @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
            val room = cap - size()
            if (room > 0) super.write(b, off, minOf(len, room))
        }
    }
}
