package com.eignex.lab.web

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.worker.*

import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** An experiment's results: each arm's totals and score, the disagreements, and every problem across the arms. */
internal fun experimentSection(
    repoUrl: String,
    job: Long?,
    arms: List<PlannedArm>,
    cases: List<CaseResult>,
    references: Map<ProblemKey, Reference> = emptyMap(),
): Html = buildHtml {
    if (arms.isEmpty()) {
        append(Html("<p class=\"muted\">Not planned yet: the runner selects the problems and writes the cases when it sets the job up.</p>"))
        return@buildHtml
    }
    val labels = arms.map { it.arm.label }
    val comparison = Compare.compare(labels, cases)
    val stats = Stats.of(labels, cases)
    val seeds = cases.map { it.seed }.distinct().sortedBy { it ?: Long.MIN_VALUE }
    val repeats = (cases.maxOfOrNull { it.repeat } ?: 0) + 1
    val total = cases.groupBy { it.arm }
    // One arm has nothing to compare against, and a reference run is the reference, so neither shows the comparison.
    val compared = arms.size > 1
    val referenceRun = arms.all { it.arm.values["backend"] == REFERENCE_BACKEND }
    if (referenceRun) {
        append(Html("<p class=\"note\">A reference run: each problem solved by its format's reference solver, every verdict added "))
        append(Html("to the <a href=\"/references\">reference results</a> as it lands, where a stronger one already there stays.</p>"))
    }
    if (mixesInstrumented(comparison.arms)) {
        append(Html("<p class=\"error\">This mixes instrumented runs (compilation measurement, marked below) with uninstrumented ones: their "))
        append(Html("times, PAR-2, time ratios and time-split scores measure the instrumentation, not the solver. Compare runtime "))
        append(Html("between uninstrumented runs only.</p>"))
    } else if (comparison.arms.any { it.instrumented > 0 }) {
        append(Html("<p class=\"note\">Instrumented runs (compilation measurement): every compilation was recorded, so times here include "))
        append(Html("that overhead and are no runtime measurement. Each case's compilation report is among its files.</p>"))
    }
    if (mixesValidation(comparison.arms)) {
        append(Html("<p class=\"error\">These arms ran under different validation policies (source-checked or unchecked, marked "))
        append(Html("below): an unchecked solver report counts as a solution or proof just as a source-checked witness does, and a "))
        append(Html("check that rejected or could not decide a claim counts it as no solution. Solved, proven and score differences "))
        append(Html("between them may measure the checking, not the solver; compare claims between arms of one policy.</p>"))
    }
    append(Html("<h2>") + (if (compared) "Arms" else "Results") + Html(" <small>") + (cases.map { it.problem }.distinct().size) + Html(" problems"))
    if (seeds.size > 1) append(Html(" × ") + seeds.size + Html(" seeds"))
    if (repeats > 1) append(Html(" × ") + repeats + Html(" repeats"))
    if (job != null) append(Html(" · <a href=\"/experiments/") + job + Html("/cases.csv\">cases.csv</a>"))
    append(Html("</small></h2>"))
    append(Html("<div class=\"scroll\"><table><tr><th>arm</th><th>commit</th><th>build / validation</th><th class=\"num\">done</th><th class=\"num\">solved</th>"))
    append(Html("<th class=\"num\">proven</th><th class=\"num\">unsupported</th><th class=\"num\">errors</th><th class=\"num\">PAR-2 s</th>"))
    if (compared) append(Html("<th class=\"num\">score</th><th>vs ") + (esc(labels.first())) + Html("</th>"))
    append(if (job != null) Html("<th>bench csv</th></tr>") else Html("</tr>"))
    val paired = stats.paired.associateBy { it.label }
    for ((index, planned) in arms.withIndex()) {
        val summary = comparison.arms[index]
        val armStats = stats.arms[index]
        val commit = commitLink(repoUrl, planned.sha)
        val versus = paired[summary.label]?.let(::versus) ?: Html("<span class=\"muted\">baseline</span>")
        val instrumented = if (summary.instrumented > 0) Html(" <span class=\"PARTIAL\" title=\"compilation measurement: times are no runtime measurement\">instrumented</span>") else ""
        append(Html("<tr><td><b>") + esc(summary.label) + Html("</b>") + instrumented + witnessLabel(summary) + Html("<br><small class=\"muted\">") + (esc(describe(planned.arm))) + Html("</small></td>"))
        append(Html("<td><code>") + commit + Html("</code></td><td>") + provenance(summary) + Html("</td>"))
        append(Html("<td class=\"num\">") + summary.cases + Html("/") + (total[summary.label]?.size ?: 0) + Html("</td>"))
        append(Html("<td class=\"num\">") + estimate(armStats.solved, "%.1f") + Html("</td><td class=\"num\">") + summary.proven + Html("</td>"))
        append(Html("<td class=\"num\">") + (if (summary.unsupported > 0) (Html("<span class=\"PARTIAL\">") + summary.unsupported + Html("</span>")) else "0") + Html("</td>"))
        append(Html("<td class=\"num\">") + (if (summary.errors > 0) (Html("<span class=\"FAILED\">") + summary.errors + Html("</span>")) else "0") + Html("</td>"))
        append(Html("<td class=\"num\">") + estimate(armStats.par2, "%.2f") + Html("</td>"))
        if (compared) append(Html("<td class=\"num\">") + estimate(armStats.score, "%.1f") + Html("</td><td>") + versus + Html("</td>"))
        if (job != null) {
            append(Html("<td>"))
            append(seeds.joinHtml(Html(" ")) { seed ->
                val name = if (seed == null) "csv" else "seed ${seed}"
                (Html("<a href=\"/experiments/") + job + Html("/bench.csv") + (query("arm" to summary.label, "seed" to seed?.toString())) + Html("\">") + name + Html("</a>"))
            })
            append(Html("</td>"))
        }
        append(Html("</tr>"))
    }
    append(Html("</table></div>"))
    append(Html("<p class=\"muted\"><small>Intervals are 95%, bootstrapped over problems, a problem's seeds and repeats averaged. "))
    append(Html("PAR-2 charges an unsolved run twice its budget."))
    if (compared) {
        append(Html(" The time ratio is the geometric mean of an arm's PAR-2 time over the first arm's, on the problems either "))
        append(Html("solved; its p is a Wilcoxon signed-rank test. Equal results whose times differ by less than 0.25 s or 10% are a "))
        append(Html("tie. Better and worse count problems by the score, with a sign test. p below 0.05 is bold."))
    }
    append(Html("</small></p>"))
    if (comparison.disagreements.isNotEmpty()) {
        append(Html("<h2 class=\"FAILED\">Disagreements <small>") + comparison.disagreements.size + Html("</small></h2><ul class=\"error\">"))
        for (d in comparison.disagreements) append(Html("<li><code>") + (esc(name(d.problem))) + Html("</code>: ") + esc(d.reason) + Html("</li>"))
        append(Html("</ul>"))
    }
    if (stats.noise.isNotEmpty()) append(noiseTable(stats.noise))
    if (references.isNotEmpty() && !referenceRun) append(referenceSection(labels, cases, references))
    append(problemGrid(labels, cases, if (referenceRun) emptyMap() else references, compared))
}

/** Each arm against the lab's reference results: who solved what, optima, gaps, and where they contradict. */
private fun referenceSection(labels: List<String>, cases: List<CaseResult>, references: Map<ProblemKey, Reference>): Html =
    buildHtml {
        val comparison = References.compare(labels, cases, references)
        append(Html("<h2>Against the reference <small>") + references.size + Html(" of ") + (cases.map { it.problem }.distinct().size) + Html(" problems have "))
        append(Html("a reference verdict · <a href=\"/references\">reference results</a></small></h2>"))
        append(Html("<div class=\"scroll\"><table><tr><th>arm</th><th class=\"num\">covered</th><th class=\"num\">both solved</th>"))
        append(Html("<th class=\"num\">only the arm</th><th class=\"num\">only the reference</th><th class=\"num\">proven optima reached</th>"))
        append(Html("<th class=\"num\">beats its best</th><th class=\"num\">worse incumbent</th><th class=\"num\">mean gap</th></tr>"))
        for (r in comparison.summaries) {
            append(Html("<tr><td><b>") + esc(r.arm) + Html("</b></td><td class=\"num\">") + r.covered + Html("</td><td class=\"num\">") + r.bothSolved + Html("</td>"))
            append(Html("<td class=\"num\">") + (if (r.armOnly > 0) (Html("<span class=\"DONE\">") + r.armOnly + Html("</span>")) else "0") + Html("</td>"))
            append(Html("<td class=\"num\">") + (if (r.referenceOnly > 0) (Html("<span class=\"FAILED\">") + r.referenceOnly + Html("</span>")) else "0") + Html("</td>"))
            append(Html("<td class=\"num\">") + r.optimaMatched + Html("/") + r.provenOptima + Html("</td><td class=\"num\">") + r.better + Html("</td>"))
            append(Html("<td class=\"num\">") + (if (r.worse > 0) (Html("<span class=\"PARTIAL\">") + r.worse + Html("</span>")) else "0") + Html("</td>"))
            append(Html("<td class=\"num\">") + (r.meanGap?.let { "%.1f%%".format(it * 100) } ?: "–") + Html("</td></tr>"))
        }
        append(Html("</table></div><p class=\"muted\"><small>The reference ran under its own budget, so this compares verdicts "))
        append(Html("and objectives, not speed. Mean gap is the relative distance to the reference objective where the arm is worse.</small></p>"))
        if (comparison.shortfalls.isNotEmpty()) {
            append(Html("<details><summary><b>Worse incumbents</b> <small class=\"muted\">") + comparison.shortfalls.size + Html(" problems where "))
            append(Html("an arm's best objective is worse than the reference's, the largest gap first</small></summary>"))
            append(Html("<div class=\"scroll\"><table><tr><th>arm</th><th>problem</th><th class=\"num\">objective</th>"))
            append(Html("<th class=\"num\">reference</th><th class=\"num\">gap</th></tr>"))
            for (f in comparison.shortfalls) {
                append(Html("<tr><td>") + esc(f.arm) + Html("</td><td><a class=\"plain\" href=\"") + problemLink(f.problem.collection, f.problem.problem) + Html("\">"))
                append(Html("<code>") + (esc(name(f.problem))) + Html("</code></a></td><td class=\"num\">") + number(f.objective) + Html("</td>"))
                append(Html("<td class=\"num\">") + number(f.reference) + (if (f.referenceProven) Html(" <small class=\"muted\">proven</small>") else "") + Html("</td>"))
                append(Html("<td class=\"num\">") + ("%.1f%%".format(f.gap * 100)) + Html("</td></tr>"))
            }
            append(Html("</table></div></details>"))
        }
        if (comparison.disagreements.isNotEmpty()) {
            append(Html("<h2 class=\"FAILED\">Disagreements with the reference <small>") + comparison.disagreements.size + Html("</small></h2><ul class=\"error\">"))
            for (d in comparison.disagreements) append(Html("<li><code>") + (esc(name(d.problem))) + Html("</code>: ") + esc(d.reason) + Html("</li>"))
            append(Html("</ul>"))
        }
    }

private fun provenance(summary: ArmSummary): Html = buildHtml {
    if (summary.buildFingerprints.isEmpty() && summary.validationPolicies.isEmpty() && summary.missingProvenance == 0) {
        append(Html("<span class=\"muted\">pending</span>"))
        return@buildHtml
    }
    if (summary.buildFingerprints.size > 1 || summary.validationPolicies.size > 1) {
        append(Html("<span class=\"PARTIAL\">mixed provenance</span><br>"))
    }
    append(summary.buildFingerprints.joinHtml(Html("<br>")) { (Html("<code title=\"") + esc(it) + Html("\">") + (esc(it.take(12))) + Html("</code>")) })
    if (summary.validationPolicies.isNotEmpty()) {
        append(Html("<br><small>") + (summary.validationPolicies.joinHtml(Html(", ")) { esc(it) }) + Html("</small>"))
    }
    if (summary.missingProvenance > 0) {
        append(Html("<br><span class=\"PARTIAL\">records missing provenance: ") + summary.missingProvenance + Html("</span>"))
    }
}

/** An arm against the baseline: the time ratio with its interval and test, then better and worse with theirs. */
private fun versus(p: Paired): Html {
    val ratio = if (p.problems == 0) Html("<span class=\"muted\">no problem solved by either</span>") else
        (Html("") + ("%.2f".format(p.timeRatio.value)) + Html("× <small class=\"muted\">") + ("%.2f".format(p.timeRatio.low)) + Html("–") + ("%.2f".format(p.timeRatio.high)) + Html("</small> ")) +
            (Html("") + pValue(p.wilcoxonP) + Html(" <small class=\"muted\">n=") + p.problems + Html("</small>"))
    return (Html("") + ratio + Html("<br><span class=\"DONE\">") + p.better + Html(" better</span> · <span class=\"FAILED\">") + p.worse + Html(" worse</span> ") + pValue(p.signP))
}

internal fun estimate(e: Estimate, format: String) =
    (Html("") + format.format(e.value) + Html(" <small class=\"muted\">") + format.format(e.low) + Html("–") + format.format(e.high) + Html("</small>"))

private fun pValue(p: Double): Html {
    val text = if (p < 0.001) Html("p<0.001") else "p=${"%.3f".format(p)}"
    return if (p < SIGNIFICANT) (Html("<b>") + text + Html("</b>")) else (Html("<small>") + text + Html("</small>"))
}

/** Problems whose runs of one arm disagree, the most unsettled first. */
private fun noiseTable(noise: List<Noise>): Html = buildHtml {
    append(Html("<h2>Noisy problems <small>") + noise.size + Html(": runs that disagree on the verdict, or whose time to best spreads over "))
    append(Html("25% of its mean</small></h2><div class=\"scroll\"><table><tr><th>problem</th><th>arm</th>"))
    append(Html("<th class=\"num\">runs</th><th class=\"num\">verdicts</th><th class=\"num\">time spread</th></tr>"))
    for (n in noise.take(NOISE_ROWS)) {
        append(Html("<tr><td><code>") + (esc(name(n.problem))) + Html("</code></td><td>") + esc(n.arm) + Html("</td><td class=\"num\">") + n.runs + Html("</td>"))
        append(Html("<td class=\"num\">") + (if (n.verdicts > 1) (Html("<span class=\"FAILED\">") + n.verdicts + Html("</span>")) else "1") + Html("</td>"))
        append(Html("<td class=\"num\">") + ("%.0f".format(n.spread * 100)) + Html("%</td></tr>"))
    }
    append(Html("</table></div>"))
    if (noise.size > NOISE_ROWS) append(Html("<p class=\"muted\">") + (noise.size - NOISE_ROWS) + Html(" more in cases.csv</p>"))
}

private const val SIGNIFICANT = 0.05

private const val NOISE_ROWS = 50

/** One row per problem, one cell per arm with its outcome on each seed; the arm that scores best on a row is marked. */
private fun problemGrid(
    labels: List<String>,
    cases: List<CaseResult>,
    references: Map<ProblemKey, Reference> = emptyMap(),
    compared: Boolean = true,
): Html = buildHtml {
    val byProblem = cases.groupBy { it.problem }
    append(Html("<h2>Problems"))
    if (compared) append(Html(" <small><label><input type=\"checkbox\" id=\"differ\"> only where arms differ</label></small>"))
    append(Html("</h2>"))
    append(Html("<div class=\"scroll\"><table class=\"grid\"><tr><th>problem</th>"))
    for (label in labels) append(Html("<th>") + esc(label) + Html("</th>"))
    if (references.isNotEmpty()) append(Html("<th class=\"ref\">reference</th>"))
    append(Html("</tr>"))
    for ((problem, ofProblem) in byProblem) {
        val outcomes = ofProblem.groupBy { it.arm }.mapValues { (_, cs) -> cs.sortedBy { it.seed ?: Long.MIN_VALUE } }
        val verdicts = labels.map { label -> outcomes[label].orEmpty().map { verdict(Outcome.of(it.record), it.status) } }
        val points = labels.associateWith { label ->
            ofProblem.filter { it.arm == label }.sumOf { mine ->
                val a = Outcome.of(mine.record) ?: return@sumOf 0.0
                ofProblem.filter { it.arm != label && it.seed == mine.seed && it.repeat == mine.repeat }.sumOf { other -> Outcome.of(other.record)?.let { Compare.points(a, it) } ?: 0.0 }
            }
        }
        val best = points.values.maxOrNull()?.takeIf { top -> points.values.any { it < top } }
        val label = (Html("<code>") + (esc(name(problem))) + Html("</code>"))
        val cell = if (problem.collection.isEmpty()) label else (Html("<a class=\"plain\" href=\"") + problemLink(problem.collection, problem.problem) + Html("\">") + label + Html("</a>"))
        append(Html("<tr data-differ=\"") + (verdicts.distinct().size > 1) + Html("\"><td>") + cell + Html("</td>"))
        for (label in labels) {
            val cls = if (best != null && points[label] == best) Html(" class=\"best\"") else ""
            append(Html("<td") + cls + Html(">"))
            append(outcomes[label].orEmpty().joinHtml(Html("<br>")) { case ->
                val outcome = Outcome.of(case.record)
                val time = outcome?.takeIf { it.rank > 0 }?.let { (Html(" <small class=\"muted\">") + ("%.2f".format(it.timeMs / 1000.0)) + Html("s</small>")) }.orEmpty()
                verdict(outcome, case.status) + time
            })
            append(Html("</td>"))
        }
        if (references.isNotEmpty()) append(Html("<td class=\"ref\">") + referenceVerdict(references[problem.referenceKey]) + Html("</td>"))
        append(Html("</tr>"))
    }
    append(Html("</table></div>"))
}

/** A reference verdict in a word, the solver beside it and its time against its own budget on hover. */
internal fun referenceVerdict(r: Reference?): Html {
    if (r == null) return Html("<span class=\"muted\">–</span>")
    val word = when {
        r.feasible == false -> "infeasible"
        r.feasible == null -> "unknown"
        r.objective != null -> number(r.objective) + if (r.proven) "*" else ""
        else -> "sat"
    }
    val title = "${r.solver}: ${"%.2f".format(r.elapsedMs / 1000.0)}s of a ${r.budgetMs / 1000}s budget"
    return (Html("<span title=\"") + esc(title) + Html("\">") + word + Html("</span> <small class=\"muted\">") + esc(r.solver) + Html("</small>"))
}

/** One outcome in a word: the objective (starred when proven), sat, infeasible, unknown, unsupported, a load error, or
 *  the case's status when it left no record; a reason shows on hover. A Solver solution claim carries how far its
 *  witness was checked ([witnessMark]). */
internal fun verdict(outcome: Outcome?, status: Status): Html = outcomeWord(outcome, status) + witnessMark(outcome)

private fun outcomeWord(outcome: Outcome?, status: Status): Html = when {
    outcome == null -> (Html("<span class=\"") + status.name + Html("\">") + status.name.lowercase() + Html("</span>"))
    outcome.error -> Html("<span class=\"FAILED\">error</span>")
    outcome.unsupported != null -> (Html("<span class=\"PARTIAL\" title=\"") + esc(outcome.unsupported) + Html("\">unsupported</span>"))
    outcome.loadError != null -> (Html("<span class=\"FAILED\" title=\"") + esc(outcome.loadError) + Html("\">load error</span>"))
    SourceValidation.rejects(outcome.sourceValidation) ->
        (Html("<span class=\"FAILED\" title=\"") + (esc(outcome.sourceValidationReason.orEmpty())) + Html("\">invalid solution</span>"))
    outcome.floatApproximation && !outcome.accepted ->
        (Html("<span class=\"PARTIAL\" title=\"") + (esc(outcome.sourceValidationReason.orEmpty())) + Html("\">unchecked grid</span>"))
    outcome.floatApproximation && outcome.feasible == true ->
        Html("<span title=\"source-checked grid witness; source optimality unproved\">") +
            (outcome.objective?.let(::number) ?: "sat") + Html(" <small class=\"muted\">grid</small></span>")
    outcome.feasible == false -> Html("infeasible")
    outcome.feasible == null -> Html("<span class=\"muted\">unknown</span>")
    outcome.optimize && outcome.objective != null -> esc(number(outcome.objective) + if (outcome.proven) "*" else "")
    else -> Html("sat")
}

/**
 * How far a Solver solution claim was checked against its original source, as a small word beside its verdict:
 * `checked` for a witness the source accepted, `unchecked` for the solver's own report (with the reason when a check
 * was asked for and its checker could not run), `check indeterminate` for a check that could not decide, the reason and
 * policy on hover. An accepted witness is a valid solution; a proof of
 * optimality beside it (the star) is still the solver's own claim. Nothing for an outcome with no solution claim, for
 * another solver's, or where the verdict already says it (an invalid solution, a float grid).
 */
internal fun witnessMark(outcome: Outcome?): Html {
    if (outcome == null || outcome.error || outcome.unsupported != null || outcome.loadError != null) return Html("")
    if (!outcome.claimedSolution || outcome.floatApproximation) return Html("")
    val policy = outcome.validationPolicy?.let { " (policy ${it})" }.orEmpty()
    return when (outcome.witness) {
        WitnessCheck.ACCEPTED -> (Html(" <small class=\"DONE\" title=\"") + esc("witness accepted against the original source${policy}: " +
            "the solution is valid; a proof of optimality is the solver's own claim") + Html("\">checked</small>"))
        WitnessCheck.UNCHECKED -> {
            val why = when (outcome.sourceValidation) {
                SourceVerdict.UNKNOWN, SourceVerdict.NOT_RUN -> "the source checker could not run: ${outcome.sourceValidationReason}"
                SourceVerdict.UNSUPPORTED -> "no external checker covers it: ${outcome.sourceValidationReason}"
                SourceVerdict.VALID, SourceVerdict.INVALID, null -> "no independent source check was asked for or performed"
            }
            (Html(" <small class=\"muted\" title=\"") + esc("the solver's own report${policy}: ${why}") + Html("\">unchecked</small>"))
        }
        WitnessCheck.INDETERMINATE -> {
            val reported = buildList {
                add("reported ${(outcome.reportedObjective ?: outcome.objective?.let(::number))?.let { "objective ${it}" } ?: "a solution"}")
                if (outcome.reportedProven || outcome.proven) add("proven")
            }.joinToString(", ")
            val reason = outcome.sourceValidationReason ?: "no result recorded"
            (Html(" <small class=\"PARTIAL\" title=\"") + esc("the source check could not decide${policy}: ${reason}; ${reported}") + Html("\">")) +
                Html("check indeterminate</small>")
        }
        WitnessCheck.INVALID, null -> Html("")
    }
}

/** An arm's witness checks in a word beside its label, the counts on hover; nothing when it claimed no solution. */
private fun witnessLabel(summary: ArmSummary): Html {
    val counts = summary.witnesses
    if (counts.isEmpty()) return Html("")
    val title = esc(WitnessCheck.entries.filter { (counts[it] ?: 0) > 0 }.joinToString(", ") { "${counts[it]} ${it.word}" } +
        " Solver solutions; a checked witness is a valid solution, not a checked proof")
    return when {
        (counts[WitnessCheck.ACCEPTED] ?: 0) > 0 || (counts[WitnessCheck.INDETERMINATE] ?: 0) > 0 ->
            (Html(" <span class=\"DONE\" title=\"") + title + Html("\">source-checked</span>"))
        else -> (Html(" <span class=\"muted\" title=\"") + title + Html("\">unchecked</span>"))
    }
}

internal fun number(value: Double) = if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()

internal fun name(problem: Problem) = if (problem.suite.isEmpty()) problem.problem else "${problem.suite}/${problem.problem}"

private fun describe(arm: Arm) = arm.values.toSortedMap().entries.joinToString(" ") { (k, v) -> "${k}=${v}" }

internal const val SEARCH_LIMIT = 200

/** Test fixtures, not benchmarks: kept in the store, left out of the coverage view. */
private val HIDDEN_COLLECTIONS = emptyList<String>()

internal fun problemLink(collection: String, problem: String) = "/problem${query("collection" to collection, "problem" to problem)}"

/** A filter dropdown that submits its form on change; [options] are (value, label). */
internal fun select(name: String, any: String, options: List<Pair<String, String>>, chosen: String?): Html = buildHtml {
    append(Html("<select name=\"") + name + Html("\" onchange=\"this.form.submit()\"><option value=\"\">") + esc(any) + Html("</option>"))
    for ((value, label) in options) {
        append(Html("<option value=\"") + esc(value) + Html("\"") + (if (value == chosen) " selected" else "") + Html(">") + esc(label) + Html("</option>"))
    }
    append(Html("</select>"))
}

/** The GitHub page of [sha] when [repoUrl] is on GitHub. */
internal fun commitUrl(repoUrl: String, sha: String): String? =
    Regex("github\\.com[:/]([^/]+/[^/]+?)(\\.git)?/?$").find(repoUrl)
        ?.let { "https://github.com/${it.groupValues[1]}/commit/${sha}" }

/** [sha] shortened, linked to its GitHub page ([commitUrl]) when [repoUrl] has one, with the whole sha as its title. */
internal fun commitLink(repoUrl: String, sha: String): Html {
    val short = esc(sha.take(9))
    val url = commitUrl(repoUrl, sha) ?: return short
    return (Html("<a href=\"") + esc(url) + Html("\" title=\"") + esc(sha) + Html("\">") + short + Html("</a>"))
}

/** An error as one line: a ref the mirror lacks reads as such, anything else is cut to its first line. */
internal fun shortError(error: String): String =
    Regex("rev-parse --verify '(.+)\\^\\{commit\\}'").find(error)?.let { "unknown ref: ${it.groupValues[1]}" }
        ?: error.lineSequence().first().let { if (it.length > 120) it.take(120) + "…" else it }

internal fun summary(
    status: StatusSettings,
    host: HostReport,
    active: List<Job>,
    nodes: Map<Long, List<JobNode>>,
    /** Free on the data directory's volume, and why the runner holds its queue ([Volumes.hold]), read before rendering. */
    free: Long?,
    held: String?,
): Html {
    val activities = active.associate { it.id to jobActivity(it, nodes[it.id].orEmpty()) }
    val waiting = activities.values.count { it == "WAITING" }
    val checking = activities.values.count { it == "CHECKING" }
    val running = active.count { it.status == Status.RUNNING } - waiting - checking
    val queued = active.count { it.status == Status.QUEUED }
    // A low disk on any volume the lab writes to, or a missing one, holds the queue ([Volumes.hold]).
    val disk = esc(free?.let { "${size(it)} free" } ?: "free space unknown") +
        (held?.let { Html(" · <span class=\"FAILED\">queue held: ") + esc(it) + Html("</span>") } ?: Html("")) + Html(" (<a href=\"/disk\">what uses it</a>)")
    val counts = "${running} running · ${queued} queued" + (if (waiting > 0) " · ${waiting} waiting for capacity" else "") +
        (if (checking > 0) " · ${checking} checking results" else "")
    return esc(counts) + Html(" · ") + disk + Html(" · ") + esc(host.host) + Html(", ") + host.cores + Html(" cores · ") +
        esc(NodeTags.hardwareProfile(host.cpu.tags))
}

/**
 * Where a job runs, as the lab placed it: the local machine alone, shared by the local machine and cloud nodes, or the
 * cloud alone; while it runs, the hosts on it now ("local + 2 nodes": the local machine when it works on it, and the
 * nodes) and each host's part of it ([hosts]).
 */
internal fun hostCell(
    job: Job,
    hosts: List<HostProgress> = emptyList(),
    nodes: List<JobNode> = emptyList(),
    /** The cloud instances it ran on ([JobDirView.instances]), read before rendering. */
    instances: List<String> = emptyList(),
): Html {
    val spec = job.experiment
    if (job.backend == Backend.NOMAD && job.status.isActive) {
        val where = when {
            nodes.isNotEmpty() && nodes.all { it.local } -> "local"
            nodes.isNotEmpty() && nodes.none { it.local } -> "cloud"
            nodes.isNotEmpty() -> "local + cloud"
            else -> spec.host.word
        }
        val detail = nodes.joinHtml(Html("<br>")) { node ->
            val href = node.anchor?.let { "/nodes#" + URLEncoder.encode(it, "UTF-8").replace("+", "%20") } ?: "/jobs/${job.id}/execution"
            (Html("<a href=\"") + esc(href) + Html("\" title=\"Nomad node ") + esc(node.id) + Html("\">") + esc(node.name) + Html("</a>")) +
                node.profile?.let { (Html("<br>") + esc(it)) }.orEmpty()
        }.ifEmpty { when {
            jobActivity(job, nodes) == "CHECKING" -> "checking results"
            !job.paused && !job.cancelRequested -> "waiting for a node"
            else -> ""
        } }
        return (Html("<span class=\"where") + (if (where == "cloud") " aws" else "") + Html("\">") + esc(where) + Html("</span>")) +
            if (detail.isEmpty()) "" else (Html("<br><small>") + detail + Html("</small>"))
    }
    if (spec.host == Experiments.AUTO_HOST) {
        val where = Html("<span class=\"where\" title=\"shared by the lab: the local machine and cloud nodes each run whole iterations of it\">local or cloud</span>")
        if (job.status != Status.RUNNING) return where
        val on = Waiting.hostsText(job.local, instances.size)
        val parts = hosts.takeIf { it.size > 1 }?.joinToString(" · ") { it.text() }
        return (Html("") + where + Html("<br><small title=\"") + (esc(instances.joinToString { it.substringBefore(' ') })) + Html("\">") + esc(on)) +
            (parts?.let { (Html("<br>") + esc(it)) } ?: "") + Html("</small>")
    }
    if (spec.host != Experiments.AWS_HOST) {
        val note = when {
            job.status == Status.QUEUED -> Html(" <small class=\"muted\">only</small>")
            else -> ""
        }
        return (Html("<span class=\"where\">local</span>") + note)
    }
    // Each line: instance, type, and how it was bought (spot or on-demand).
    val shapes = instances.map { it.split(' ') }.groupingBy { "${it.getOrNull(1) ?: "instance"} ${it.getOrNull(2) ?: ""}".trim() }.eachCount()
    val detail = when {
        instances.isNotEmpty() -> "${instances.size} ${if (instances.size == 1) "node" else "nodes"}: " +
            shapes.entries.joinToString(", ") { (shape, n) -> if (n == 1) shape else "${n} × ${shape}" }
        // A cancel-requested or paused job waits for nothing: no node takes it.
        job.status == Status.QUEUED && !job.cancelRequested && !job.paused -> "waiting for a node"
        else -> null
    }
    return Html("<span class=\"where aws\">cloud</span>") +
        (detail?.let { (Html("<br><small title=\"") + (esc(instances.joinToString { it.substringBefore(' ') })) + Html("\">") + esc(it) + Html("</small>")) } ?: "")
}

/** A job's status with its notes: cancelling, paused, its place in the queue, its priority and its error. Why a queued
 *  one waits is left to `lab placement`. */
internal fun statusCell(job: Job, position: Int?, nodes: List<JobNode>? = null): Html {
    val activity = nodes?.let { jobActivity(job, it) }
    val partial = job.status == Status.DONE && job.failed > 0
    val label = if (partial) {
        (Html("DONE · <a href=\"/jobs/") + job.id + Html("?failed\">") + job.failed + Html(" failed</a>"))
    } else {
        activity ?: job.status.name
    }
    val notes = listOfNotNull(
        "cancelling".takeIf { job.cancelRequested && job.status.isActive },
        Html("<span class=\"badge\">paused</span>").takeIf { job.paused && job.status.isActive },
        "#${position} in the queue".takeIf { position != null },
        "priority ${job.priority}".takeIf { job.priority != 0 },
        "waiting for compatible capacity".takeIf { activity == "WAITING" },
        "ingesting results and checking outputs".takeIf { activity == "CHECKING" },
        job.error?.let { (Html("<span title=\"") + esc(it) + Html("\">") + (esc(shortError(it))) + Html("</span>")) },
    )
    val cls = when { partial -> "PARTIAL"; activity in setOf("WAITING", "CHECKING") -> "QUEUED"; else -> job.status.name }
    return (Html("<span class=\"") + cls + Html("\">") + label + Html("</span>")) + notes.joinHtml { (Html("<br><small>") + it + Html("</small>")) }
}

/** The ref and the commit it ran, without repeating a ref that is the commit itself. */
internal fun refText(repoUrl: String, job: Job): Html {
    val isSha = Regex("[0-9a-f]{7,40}").matches(job.ref)
    val sha = job.sha ?: job.ref.takeIf { isSha }
    val commit = sha?.let { full ->
        val spec = job.experiment
        val repository = if (job.backend == Backend.LEGACY || !spec.isBench && !spec.isProfile) repoUrl
            else Repositories.url(spec.repository)
        commitLink(repository, full)
    }
    return Html("<code>") + listOfNotNull(esc(job.ref).takeUnless { isSha }, commit).joinHtml(Html(" ")) { it } + Html("</code>")
}

internal fun progress(job: Job): Html {
    val total = job.total.coerceAtLeast(1)
    val running = job.running
    fun part(cls: String, count: Int) = if (count == 0) "" else (Html("<i class=\"") + cls + Html("\" style=\"width:") + (100.0 * count / total) + Html("%\"></i>"))
    val bar = (Html("<span class=\"bar\">") + part("ok", job.done - job.failed) + part("bad", job.failed) + part("run", running) + Html("</span>"))
    val parallel = if (job.parallel > 1) " ×${job.parallel}" else ""
    return (Html("") + bar + Html("<small>") + job.done + Html("/") + job.total + parallel + Html("</small>"))
}

internal fun output(jobId: Long, files: JobDirView, name: String, running: Boolean): Html {
    val bytes = files.outputs[name] ?: return (Html("<span class=\"muted\">") + name.substringAfter('.') + Html("</span>"))
    val link = if (running) "${name}?tail=${TAIL_BYTES}" else name
    return (Html("<a href=\"/jobs/") + jobId + Html("/files/") + link + Html("\">") + name.substringAfter('.') + Html("</a> <small class=\"muted\">") + size(bytes) + Html("</small>"))
}

internal val clock = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

/** A time to come, as a clock time; the exact time is in the tooltip. */
internal fun clockTime(millis: Long): Html =
    (Html("<span title=\"") + (stamp.format(Instant.ofEpochMilli(millis))) + Html("\">") + (clock.format(Instant.ofEpochMilli(millis))) + Html("</span>"))

/** How long ago, for the last day, then the date; the exact time is in the tooltip. */
internal fun ago(millis: Long?): Html {
    if (millis == null) return Html("")
    val seconds = (now() - millis) / 1000
    val text = if (seconds < 86_400) "${span(seconds)} ago" else clock.format(Instant.ofEpochMilli(millis))
    return (Html("<span title=\"") + (stamp.format(Instant.ofEpochMilli(millis))) + Html("\">") + text + Html("</span>"))
}

/** A job's running time as h:mm:ss, its waits in the queue left out. */
internal fun elapsed(job: Job): String = if (job.startedAt == null) "" else clock(job.elapsedMs(now()) / 1000)

internal fun clock(seconds: Long) = "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)

internal fun span(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m"
    seconds < 86_400 -> "${seconds / 3600}h${if (seconds / 60 % 60 > 0) " ${seconds / 60 % 60}m" else ""}"
    else -> "${seconds / 86_400}d${if (seconds / 3600 % 24 > 0) " ${seconds / 3600 % 24}h" else ""}"
}

internal fun query(vararg params: Pair<String, String?>): String = params
    .filter { it.second != null }
    .joinToString(Html("&")) { (key, value) -> "${key}=${URLEncoder.encode(value, Charsets.UTF_8)}" }
    .let { if (it.isEmpty()) "" else "?${it}" }

internal fun esc(text: String): Html = Html(text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"))

/**
 * Markup the pages may put in a page as it is: what [esc] made of text, or what a renderer built of escaped parts. Pages
 * build markup as `Html("<td>") + value + Html("</td>")`, never as a string template, so a value is escaped unless it
 * is already Html: escaping is the default, and leaving it out takes saying the value is markup.
 */
@JvmInline
internal value class Html(val markup: String) : CharSequence by markup {
    /** [this] followed by [value]: as it is when it is Html, escaped otherwise. */
    operator fun plus(value: Any?): Html = Html(markup + if (value is Html) value.markup else esc(value.toString()).markup)

    override fun toString() = markup
}

/** [this], or no markup at all. */
internal fun Html?.orEmpty(): Html = this ?: Html("")

/** [this]'s items as markup, [separator] between them. */
internal fun <T> Iterable<T>.joinHtml(separator: Html = Html(""), transform: (T) -> Html): Html =
    Html(joinToString(separator.markup) { transform(it).markup })

/**
 * Builds a page or a part of one from markup only: it takes Html and nothing else, so text reaches a page through [esc]
 * or a template's escaped value ([Html.plus]), never as it is.
 */
internal class HtmlBuilder {
    private val out = StringBuilder()

    fun append(markup: Html): HtmlBuilder = apply { out.append(markup.markup) }

    fun build(): Html = Html(out.toString())
}

/** Markup built by [block] ([HtmlBuilder]). */
internal inline fun buildHtml(block: HtmlBuilder.() -> Unit): Html = HtmlBuilder().apply(block).build()

private const val TAIL_BYTES = 65_536

/** The lab's views, as the tab bar shows them. */
internal enum class Tab(val label: String, val href: String) {
    QUEUE("Queue", "/"), NODES("Nodes", "/nodes"), REGRESSION("Regression", "/trend"), REFERENCE("Reference", "/references")
}

internal fun head(title: String, live: Boolean, tab: Tab = Tab.QUEUE): Html = Html("""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>${esc(title)}</title><style>$STYLE</style></head>
<body${if (live) " data-refresh" else ""}><nav class="tabs"><a class="brand" href="/">eignex lab</a>${tabs(tab)}</nav>""")

private fun tabs(active: Tab): Html = Tab.entries.joinHtml {
    (Html("<a href=\"") + it.href + Html("\"") + (if (it == active) Html(" class=\"on\" aria-current=\"page\"") else "") + Html(">") + it.label + Html("</a>"))
}

private const val STYLE = """
:root{--bg:#fff;--fg:#111;--muted:#666;--line:#ddd;--soft:#f3f3f3;--link:#0969da;
--run:#0550ae;--ok:#1a7f37;--warn:#9a6700;--bad:#cf222e;--off:#888;--series-1:#2a78d6;--series-2:#eb6834}
@media (prefers-color-scheme: dark){:root{--bg:#111;--fg:#ddd;--muted:#999;--line:#333;--soft:#222;--link:#58a6ff;
--run:#6cb6ff;--ok:#3fb950;--warn:#d29922;--bad:#f85149;--off:#888;--series-1:#3987e5;--series-2:#d95926}}
body{font:14px system-ui,sans-serif;margin:0 auto;max-width:1440px;padding:24px 32px 48px;background:var(--bg);color:var(--fg)}
@media (max-width:700px){body{padding:16px}}
a{color:var(--link)}a.plain{color:inherit;text-decoration:none}a.plain:hover{text-decoration:underline}
h1{margin:0 0 4px}h1 code{font-size:inherit}h1 a{color:inherit;text-decoration:none}h2{font-size:16px;margin:32px 0 10px}h2 small{font-weight:normal}
.muted{color:var(--muted)}p.description{margin:4px 0 0;max-width:80ch}
td .desc{color:var(--muted);font-size:.85em;max-width:60ch;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.scroll{overflow-x:auto}
table{border-collapse:collapse;width:100%}td,th{padding:6px 12px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}
th.num,td.num{text-align:right}tr.sub td{border-top:0;padding-top:0;font-size:12px}tr.sub div{margin:2px 0}
code{font-size:12px;word-break:break-all}
.RUNNING{color:var(--run)}.DONE{color:var(--ok)}.PARTIAL{color:var(--warn)}.FAILED{color:var(--bad)}.CANCELLED,.QUEUED{color:var(--off)}
.PARTIAL a{color:inherit}
.badge{background:var(--warn);color:var(--bg);border-radius:3px;padding:0 4px}
.where{border:1px solid var(--line);border-radius:3px;padding:0 4px;white-space:nowrap}.kind{font-size:.8em;color:var(--muted);border:1px solid var(--line);border-radius:3px;padding:0 4px;white-space:nowrap}.where.aws{border-color:var(--link);color:var(--link)}
.bar{display:flex;width:90px;height:6px;margin:6px 0 2px;background:var(--soft);border-radius:3px;overflow:hidden}
.bar i{display:block}.bar .ok{background:var(--ok)}.bar .bad{background:var(--bad)}.bar .run{background:var(--run)}
td .est{color:var(--muted);font-size:12px}.small{font-size:12px}.warn{color:var(--warn)}
.work{display:flex;flex-direction:column;gap:4px;margin:0 0 12px;line-height:1.5;overflow-wrap:anywhere}
.gauge{display:inline-block;width:120px;max-width:30vw;height:6px;background:var(--soft);border-radius:3px;overflow:hidden;vertical-align:middle}
.gauge i{display:block;height:100%;background:var(--run)}.gauge i.over{background:var(--warn)}
.tools{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:8px}.tools input{font:inherit;padding:3px 6px;min-width:200px}
button{font:inherit;padding:3px 10px;border:1px solid var(--line);border-radius:4px;background:var(--soft);color:var(--fg);cursor:pointer}
pre.script{background:var(--soft);padding:8px;overflow-x:auto;max-width:120ch}table.board tr.total td{font-weight:600;border-bottom:2px solid var(--line)}.chip.on{border-color:var(--link);color:var(--link)}button.danger{color:var(--bad);border-color:var(--bad)}
.actions{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:12px 0}.actions input{width:4em;font:inherit}
dl.meta{display:grid;grid-template-columns:max-content 1fr;gap:4px 16px;margin:12px 0}dl.meta dt{color:var(--muted)}dl.meta dd{margin:0}
table.grid{width:auto}table.grid td{min-width:110px}
table.grid .ref{border-left:1px solid var(--line);color:var(--muted)}
table.grid td.best{background:color-mix(in srgb,var(--ok) 14%,transparent)}ul.error{color:var(--bad)}
pre.error{color:var(--bad);white-space:pre-wrap;word-break:break-all;background:var(--soft);padding:8px}
.pager{margin-top:12px}
p.note{background:var(--soft);border-left:3px solid var(--link);padding:8px 12px;margin:16px 0}
nav.tabs{display:flex;align-items:center;gap:4px;border-bottom:1px solid var(--line);margin:-8px 0 20px;flex-wrap:wrap}
nav.tabs a{padding:8px 14px;color:var(--muted);text-decoration:none;border-bottom:2px solid transparent;margin-bottom:-1px}
nav.tabs a:hover{color:var(--fg)}nav.tabs a.on{color:var(--fg);border-bottom-color:var(--link);font-weight:600}
nav.tabs a.brand{color:var(--fg);font-weight:700;padding-left:0;margin-right:12px}
a.chip{text-decoration:none;display:inline-block}
tr.family{cursor:pointer}tr.family:hover td{background:var(--soft)}
.filters{align-items:center}.filters input[type=checkbox]{min-width:0;margin:0 4px 0 0}.filters label{display:inline-flex;align-items:center;white-space:nowrap}.filters select{font:inherit;padding:3px 6px;background:var(--bg);color:var(--fg);border:1px solid var(--line);border-radius:4px}
#node-request{align-items:center;gap:12px}#node-request label{display:flex;align-items:center;gap:6px;flex-wrap:wrap}
#node-request select{font:inherit;padding:4px;max-width:100%;background:var(--bg);color:var(--fg);border:1px solid var(--line);border-radius:4px}
#node-request input{min-width:0}#node-request label:first-child{flex-basis:100%}#node-pool{min-width:0}
.chart svg,.multiple svg{display:block}.chart{width:100%}
.grid{stroke:var(--line);stroke-width:1}.tick{fill:var(--muted);font-size:11px}.tick.mono{font-family:ui-monospace,monospace}
.endlabel{fill:var(--fg);font-size:12px}.cross{stroke:var(--muted);stroke-width:1}
.legend{display:flex;gap:16px;font-size:12px;color:var(--muted);margin:-4px 0 6px}
.legend i{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:5px;vertical-align:-1px}
.legend i.hollow{border:2px solid var(--series-1);width:6px;height:6px}.legend i.flag{border:2px solid var(--bad);width:6px;height:6px;background:none}
.multiples{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:12px 20px}
.mtitle{font-size:12px;color:var(--muted);margin-bottom:2px}
.tip{position:absolute;z-index:5;background:var(--bg);border:1px solid var(--line);border-radius:6px;padding:6px 8px;
font-size:12px;box-shadow:0 2px 8px rgba(0,0,0,.15);pointer-events:none;min-width:180px}
.tip div{display:flex;justify-content:space-between;gap:12px}.tip span{color:var(--muted)}
"""

/** Filtering, actions and a refresh that swaps the live parts in place, so scrolling, a selection and a half-typed
 *  input survive it. A page stops refreshing once its fresh copy says nothing is live any more. */
internal const val SCRIPT = """<script>
let chip = 'all';
function applyFilter() {
  const q = document.getElementById('q');
  const text = q ? q.value.trim().toLowerCase() : '';
  document.querySelectorAll('tr[data-state]').forEach(function (row) {
    row.hidden = !((!text || row.dataset.text.includes(text)) && (chip === 'all' || row.dataset.state === chip));
  });
}
document.getElementById('q')?.addEventListener('input', applyFilter);
function applyDiffer() {
  const only = document.getElementById('differ')?.checked;
  document.querySelectorAll('tr[data-differ]').forEach(function (row) { row.hidden = only && row.dataset.differ !== 'true'; });
}
document.addEventListener('change', function (e) { if (e.target.id === 'differ') applyDiffer(); });
document.querySelectorAll('[data-chip]').forEach(function (button) {
  button.addEventListener('click', function () {
    chip = button.dataset.chip;
    document.querySelectorAll('[data-chip]').forEach(function (b) { b.classList.toggle('on', b === button); });
    applyFilter();
  });
});
async function act(path, body, ask) {
  if (ask && !confirm(ask)) return;
  const response = await fetch(path, body
    ? {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)}
    : {method: 'POST'});
  if (!response.ok) alert(await response.text());
  location.reload();
}
let timer = null;
async function refresh() {
  if (document.hidden || String(getSelection()).length) return;
  if (document.activeElement && document.activeElement.closest('[data-live]')) return;
  try {
    const response = await fetch(location.href, {headers: {Accept: 'text/html'}});
    if (!response.ok) return;
    const fresh = new DOMParser().parseFromString(await response.text(), 'text/html');
    fresh.querySelectorAll('[data-live]').forEach(function (part) {
      const old = document.getElementById(part.id);
      if (old) old.innerHTML = part.innerHTML;
    });
    applyFilter();
    applyDiffer();
    if (!fresh.body.hasAttribute('data-refresh')) clearInterval(timer);
  } catch (e) {}
}
if (document.body.hasAttribute('data-refresh')) timer = setInterval(refresh, 10000);
</script>"""

internal fun preparationDetails(report: ExecutionSessionReport, at: Long = now()): Html {
    val status = report.preparation ?: return Html("")
    val snapshot = status.progress
    val age = (at - status.updatedAt).coerceAtLeast(0)
    val live = report.phase == "preparing" && report.session.desired != Desired.STOP && age <= 5000
    fun seconds(ms: Long) = String.format(Locale.ROOT, "%.2f s", ms / 1000.0)
    return buildHtml {
        append(Html("<strong>") + (esc(PreparationTrace.labels.getValue(snapshot.stage))) + Html("</strong>"))
        append(Html(" · ") + (seconds(snapshot.stageElapsedMs + if (live) age else 0)) + Html(" in phase"))
        if (report.phase == "preparing" && age > 5000) append(Html(" · last update ") + seconds(age) + Html(" ago"))
        append(Html("<br>"))
        append(snapshot.timings.filterValues { it > 0 }.entries.joinHtml(Html(" · ")) {
            esc(PreparationTrace.labels.getValue(it.key)) + Html(": ") + seconds(it.value)
        })
        status.diagnostics?.let { evidence ->
            append(Html("<details><summary>Last preparation diagnostics (provisional)</summary><pre>") +
                esc(kotlinx.serialization.json.Json.encodeToString(PreparationDiagnostics.serializer(), evidence)) + Html("</pre></details>"))
        }
        if (snapshot.caches.isNotEmpty()) {
            append(Html("<br>"))
            append(snapshot.caches.entries.joinHtml(Html(" · ")) { (name, event) ->
                buildHtml {
                    append(Html("") + (esc(PreparationTrace.cacheLabels.getValue(name))) + Html(": latest ") + event.outcome + Html(" (") + (esc(PreparationTrace.cacheReasons.getValue(event.reason))) + Html(")"))
                    if (event.hits.toLong() + event.misses + event.bypasses > 1) append(Html(", ") + event.hits + Html(" hits / ") + event.misses + Html(" misses / ") + event.bypasses + Html(" bypasses"))
                    if (event.verificationMs > 0) append(Html(", verification ") + seconds(event.verificationMs))
                    if (event.waitingMs > 0) append(Html(", waiting ") + seconds(event.waitingMs))
                    if (event.checkedFiles > 0) append(Html(", checked ") + event.checkedFiles + Html(" files"))
                    if (event.hashedFiles > 0) append(Html(", rehashed ") + event.hashedFiles + Html(" files / ") + event.hashedBytes + Html(" bytes"))
                }
            })
        }
    }
}
