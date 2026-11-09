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
    append(Html("<div class=\"scroll\"><table class=\"arms-table\"><tr><th>arm</th><th>commit</th><th>build / validation</th><th class=\"num\">done</th><th class=\"num\">solved</th>"))
    append(Html("<th class=\"num\">eligible native proofs</th><th>proof evidence</th><th class=\"num\">unsupported</th><th class=\"num\">errors</th><th class=\"num\">PAR-2 s</th>"))
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
        append(Html("<td><details id=\"proof-evidence-") + index + Html("\"><summary>") + summary.nativeProofClaims + Html(" native claims</summary><p>") +
            summary.contradictedOptima + Html(" contradicted; ") + summary.unverifiedOptima + Html(" unverified scope/check</p></details>"))
        if (summary.contradictedOptima > 0) append(Html("<span class=\"FAILED\">") + summary.contradictedOptima + Html(" contradicted optima</span>"))
        if (summary.unverifiedOptima > 0) append(Html("<span class=\"PARTIAL\">") + summary.unverifiedOptima + Html(" unverified scope/check</span>"))
        append(Html("</td>"))
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
    append(Html("<details id=\"scoring-notes\" class=\"scoring-notes\"><summary>How to read these results</summary><p class=\"muted\"><small>Intervals are 95%, bootstrapped over problems, a problem's seeds and repeats averaged. "))
    append(Html("PAR-2 charges an unsolved run twice its budget."))
    append(Html(" Checked witnesses establish feasible points. Eligible native proofs exclude contradicted optima and unavailable scope/checks; absence of a counterexample is not an independent proof."))
    if (compared) {
        append(Html(" The time ratio is the geometric mean of an arm's PAR-2 time over the first arm's, on the problems either "))
        append(Html("solved; its p is a Wilcoxon signed-rank test. Equal results whose times differ by less than 0.25 s or 10% are a "))
        append(Html("tie. Better and worse count problems by the score, with a sign test. p below 0.05 is bold."))
    }
    append(Html("</small></p></details>"))
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
    append(Html("<details class=\"provenance\" id=\"provenance-") + URLEncoder.encode(summary.label, Charsets.UTF_8) + Html("\"><summary>") + summary.buildFingerprints.size + Html(" builds · ") + summary.validationPolicies.size + Html(" policies</summary><div>"))
    append(summary.buildFingerprints.joinHtml(Html("<br>")) { Html("<code>") + it + Html("</code>") })
    if (summary.validationPolicies.isNotEmpty()) {
        append(Html("<p>") + summary.validationPolicies.joinHtml(Html("<br>")) { esc(it) } + Html("</p>"))
    }
    append(Html("</div></details>"))
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
    append(Html("<details id=\"noisy-problems\" class=\"result-diagnostics\"><summary><b>Noisy problems</b> · ") + noise.size + Html("</summary>"))
    append(Html("<p class=\"muted\">Runs that disagree on the verdict, or whose time to best spreads over 25% of its mean.</p>"))
    append(Html("<div class=\"scroll\"><table><tr><th>problem</th><th>arm</th>"))
    append(Html("<th class=\"num\">runs</th><th class=\"num\">verdicts</th><th class=\"num\">time spread</th></tr>"))
    for (n in noise.take(NOISE_ROWS)) {
        append(Html("<tr><td><code>") + (esc(name(n.problem))) + Html("</code></td><td>") + esc(n.arm) + Html("</td><td class=\"num\">") + n.runs + Html("</td>"))
        append(Html("<td class=\"num\">") + (if (n.verdicts > 1) (Html("<span class=\"FAILED\">") + n.verdicts + Html("</span>")) else "1") + Html("</td>"))
        append(Html("<td class=\"num\">") + ("%.0f".format(n.spread * 100)) + Html("%</td></tr>"))
    }
    append(Html("</table></div>"))
    if (noise.size > NOISE_ROWS) append(Html("<p class=\"muted\">") + (noise.size - NOISE_ROWS) + Html(" more in cases.csv</p>"))
    append(Html("</details>"))
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
    append(Html("<section id=\"problems\" data-result-list><h2>Problems <small>") + byProblem.size + Html(" total</small>"))
    if (compared) append(Html(" <small><label><input type=\"checkbox\" id=\"differ\"> only where arms differ</label></small>"))
    append(Html("</h2>"))
    append(resultFilters("problem"))
    append(Html("<div class=\"scroll problem-scroll\" tabindex=\"0\" aria-label=\"Problem results\"><table class=\"grid\"><tr><th>problem</th>"))
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
        append(Html("<tr data-result-row data-search=\"") + name(problem) + Html("\" data-differ=\"") + (verdicts.distinct().size > 1) + Html("\"><td>") + cell + Html("</td>"))
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
    append(Html("</table></div><p class=\"muted\" data-result-count aria-live=\"polite\"></p><p data-result-empty hidden>No matching problems.</p></section>"))
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

internal fun problemLink(collection: String, problem: String) = "/problem${query("collection" to collection, "problem" to problem)}"

/** A filter dropdown; [options] are (value, label), [submitOnChange] preserves immediate filters by default. */
internal fun select(name: String, any: String, options: List<Pair<String, String>>, chosen: String?, submitOnChange: Boolean = true): Html = buildHtml {
    append(Html("<select name=\"") + name + Html("\"") + (if (submitOnChange) Html(" onchange=\"this.form.submit()\"") else Html("")) + Html("><option value=\"\">") + esc(any) + Html("</option>"))
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
    return buildHtml {
        append(Html("<div class=\"queue-overview\" role=\"group\" aria-label=\"") + esc(counts) + Html("\">"))
        for ((count, label, cls) in listOf(
            Triple(running, "Running", "RUNNING"), Triple(queued, "Queued", "QUEUED"),
            Triple(waiting, "Waiting for capacity", "capacity-wait"), Triple(checking, "Checking results", "QUEUED"),
        )) {
            append(Html("<div class=\"queue-stat ") + cls + Html("\"><strong>") + count + Html("</strong><span>") + label + Html("</span></div>"))
        }
        append(Html("</div><div class=\"queue-capacity muted small\"><span>") + disk + Html("</span><span>Local host: ") + esc(host.host) + Html(" · ") + host.cores + Html(" cores · ") +
            esc(NodeTags.hardwareProfile(host.cpu.tags)) + Html("</span></div>"))
    }
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
        val on = Waiting.hostsText(job.local, 0)
        val parts = hosts.takeIf { it.size > 1 }?.joinToString(" · ") { it.text() }
        return (Html("") + where + Html("<br><small>") + esc(on)) +
            (parts?.let { (Html("<br>") + esc(it)) } ?: "") + Html("</small>")
    }
    if (spec.host != Experiments.AWS_HOST) {
        val note = when {
            job.status == Status.QUEUED -> Html(" <small class=\"muted\">only</small>")
            else -> ""
        }
        return (Html("<span class=\"where\">local</span>") + note)
    }
    val detail = when {
        // A cancel-requested or paused job waits for nothing: no node takes it.
        job.status == Status.QUEUED && !job.cancelRequested && !job.paused -> "waiting for a node"
        else -> null
    }
    return Html("<span class=\"where aws\">cloud</span>") +
        (detail?.let { (Html("<br><small>") + esc(it) + Html("</small>")) } ?: "")
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
        val repository = if (!spec.isBench && !spec.isProfile) repoUrl
            else Repositories.url(spec.repository)
        commitLink(repository, full)
    }
    return Html("<code>") + listOfNotNull(esc(job.ref).takeUnless { isSha }, commit).joinHtml(Html(" ")) { it } + Html("</code>")
}

internal fun progress(job: Job): Html = buildHtml {
    val total = job.total.coerceAtLeast(1)
    val running = job.running
    fun part(cls: String, count: Int): Html = if (count == 0) Html("") else
        Html("<i class=\"") + cls + Html("\" style=\"width:") + (100.0 * count / total) + Html("%\"></i>")
    append(Html("<span class=\"bar\" role=\"img\" aria-label=\""))
    append(esc("${job.done} of ${job.total} completed, ${job.failed} failed, $running running"))
    append(Html("\">"))
    append(part("ok", job.done - job.failed))
    append(part("bad", job.failed))
    append(part("run", running))
    append(Html("</span><small>") + job.done + Html("/") + job.total)
    if (job.vcpus > 1) append(Html(" ") + job.vcpus + Html(" vCPU"))
    append(Html("</small>"))
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

/** Small native controls reused by the problem grid and benchmark case disclosures. */
internal fun resultFilters(id: String, arms: List<String> = emptyList(), statuses: List<String> = emptyList()): Html = buildHtml {
    append(Html("<div class=\"result-filters\"><label>Search <input type=\"search\" id=\"") + id + Html("-query\" data-result-query placeholder=\"Problem, path or case\"></label>"))
    if (arms.isNotEmpty()) {
        append(Html("<label>Arm <select id=\"") + id + Html("-arm\" data-result-arm><option value=\"\">All arms</option>"))
        arms.forEach { append(Html("<option>") + it + Html("</option>")) }
        append(Html("</select></label>"))
    }
    if (statuses.isNotEmpty()) {
        append(Html("<label>Status <select id=\"") + id + Html("-status\" data-result-status><option value=\"\">All statuses</option>"))
        statuses.forEach { append(Html("<option>") + it + Html("</option>")) }
        append(Html("</select></label>"))
    }
    append(Html("</div>"))
}
