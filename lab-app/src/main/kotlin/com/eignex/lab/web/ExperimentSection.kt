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
import kotlinx.html.*

/**
 * An experiment's results: each arm's totals and score, the disagreements, and every problem across
 * the arms.
 */
internal fun experimentSection(
    repoUrl: String,
    job: Long?,
    arms: List<PlannedArm>,
    cases: List<CaseResult>,
    references: Map<ProblemKey, Reference> = emptyMap(),
): Html = buildHtml {
    if (arms.isEmpty()) {
        p(classes = "muted") {
            text(
                "Not planned yet: the runner selects the problems and writes the cases when it sets the job up.",
            )
        }
        return@buildHtml
    }
    val labels = arms.map { it.arm.label }
    val comparison = Compare.compare(labels, cases)
    val stats = Stats.of(labels, cases)
    val seeds = cases.map { it.seed }.distinct().sortedBy { it ?: Long.MIN_VALUE }
    val repeats = (cases.maxOfOrNull { it.repeat } ?: 0) + 1
    val total = cases.groupBy { it.arm }
    // One arm has nothing to compare against, and a reference run is the reference, so neither
    // shows the comparison.
    val compared = arms.size > 1
    val referenceRun = arms.all { it.arm.values["backend"] == REFERENCE_BACKEND }
    if (referenceRun) {
        p(classes = "note") {
            text(
                "A reference run: each problem solved by its format's reference solver, every verdict added ",
            )
            text("to the ")
            a(href = "/references") {
                text("reference results")
            }
            text(" as it lands, where a stronger one already there stays.")
        }
    }
    if (mixesInstrumented(comparison.arms)) {
        p(classes = "error") {
            text(
                "This mixes instrumented runs (compilation measurement, marked below) with uninstrumented ones: their ",
            )
            text(
                "times, PAR-2, time ratios and time-split scores measure the instrumentation, not the solver. Compare runtime ",
            )
            text("between uninstrumented runs only.")
        }
    } else if (comparison.arms.any { it.instrumented > 0 }) {
        p(classes = "note") {
            text(
                "Instrumented runs (compilation measurement): every compilation was recorded, so times here include ",
            )
            text(
                "that overhead and are no runtime measurement. Each case's compilation report is among its files.",
            )
        }
    }
    if (mixesValidation(comparison.arms)) {
        p(classes = "error") {
            text(
                "These arms ran under different validation policies (source-checked or unchecked, marked ",
            )
            text(
                "below): an unchecked solver report counts as a solution or proof just as a source-checked witness does, and a ",
            )
            text(
                "check that rejects a claim counts it as no solution; unavailable checks retain native claims. Solved, fully decided and score differences ",
            )
            text(
                "between them may measure the checking, not the solver; compare claims between arms of one policy.",
            )
        }
    }
    h2 {
        emit(if (compared) "Arms" else "Results")
        text(" ")
        small {
            emit(cases.map { it.problem }.distinct().size)
            text(" problems")
            if (seeds.size > 1) {
                text(" × ")
                emit(seeds.size)
                text(" seeds")
            }
            if (repeats > 1) {
                text(" × ")
                emit(repeats)
                text(" repeats")
            }
            if (job != null) {
                text(" · ")
                a(href = "/experiments/${job}/cases.csv") {
                    text("cases.csv")
                }
            }
        }
    }
    div(classes = "scroll") {
        table(classes = "arms-table") {
            tr {
                th { text("arm") }
                th { text("commit") }
                th { text("build / validation") }
                th(classes = "num") {
                    text("done")
                }
                th(classes = "num") {
                    text("solved")
                }
                th(classes = "num") {
                    text("fully decided")
                }
                th { text("proof evidence") }
                th(classes = "num") {
                    text("unsupported")
                }
                th(classes = "num") {
                    text("errors")
                }
                th(classes = "num") {
                    text("PAR-2 s")
                }
                if (compared) {
                    th(classes = "num") {
                        text("score")
                    }
                    th {
                        text("vs ")
                        emit(labels.first())
                    }
                }
                if (job != null) th { +"bench csv" }
            }
            val paired = stats.paired.associateBy { it.label }
            for ((index, planned) in arms.withIndex()) {
                val summary = comparison.arms[index]
                val armStats = stats.arms[index]
                val commit = commitLink(repoUrl, planned.sha)
                val versus =
                    paired[summary.label]?.let(::versus)
                        ?: buildHtml {
                            span(classes = "muted") {
                                text("baseline")
                            }
                        }
                val instrumented =
                    if (summary.instrumented > 0)
                        buildHtml {
                            text(" ")
                            span(classes = "PARTIAL") {
                                attributes["title"] =
                                    "compilation measurement: times are no runtime measurement"
                                text("instrumented")
                            }
                        }
                    else ""
                tr {
                    td {
                        b { emit(summary.label) }
                        emit(instrumented)
                        emit(witnessLabel(summary))
                        br {}
                        small(classes = "muted") {
                            emit(describe(planned.arm))
                        }
                    }
                    td { code { emit(commit) } }
                    td { emit(provenance(summary)) }
                    td(classes = "num") {
                        emit(summary.cases)
                        text("/")
                        emit(total[summary.label]?.size ?: 0)
                    }
                    td(classes = "num") {
                        emit(estimate(armStats.solved, "%.1f"))
                    }
                    td(classes = "num") {
                        emit(summary.proven)
                    }
                    td {
                        details {
                            attributes["id"] = "proof-evidence-${index}"
                            summary {
                                emit(summary.nativeProofClaims)
                                text(" native claims")
                            }
                            p {
                                emit(summary.contradictedOptima)
                                text(" contradicted; ")
                                emit(summary.unverifiedOptima)
                                text(" native only")
                            }
                        }
                        if (summary.contradictedOptima > 0)
                            span(classes = "FAILED") {
                                emit(summary.contradictedOptima)
                                text(" contradicted optima")
                            }
                        if (summary.unverifiedOptima > 0)
                            span {
                                emit(summary.unverifiedOptima)
                                text(" native only")
                            }
                    }
                    td(classes = "num") {
                        emit(
                            if (summary.unsupported > 0)
                                buildHtml {
                                    span(classes = "PARTIAL") {
                                        emit(summary.unsupported)
                                    }
                                }
                            else "0",
                        )
                    }
                    td(classes = "num") {
                        emit(
                            if (summary.errors > 0)
                                buildHtml {
                                    span(classes = "FAILED") {
                                        emit(summary.errors)
                                    }
                                }
                            else "0",
                        )
                    }
                    td(classes = "num") {
                        emit(estimate(armStats.par2, "%.2f"))
                    }
                    if (compared) {
                        td(classes = "num") {
                            emit(estimate(armStats.score, "%.1f"))
                        }
                        td { emit(versus) }
                    }
                    if (job != null) {
                        td {
                            emit(
                                seeds.joinHtml(buildHtml { text(" ") }) { seed ->
                                    val name = if (seed == null) "csv" else "seed ${seed}"
                                    buildHtml {
                                        a(
                                            href =
                                                "/experiments/${job}/bench.csv${(query(
                                                                "arm" to summary.label,
                                                                "seed" to seed?.toString(),
                                                            ))
                                                            }",
                                        ) {
                                            emit(name)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    details(classes = "scoring-notes") {
        attributes["id"] = "scoring-notes"

        summary { text("How to read these results") }
        p(classes = "muted") {
            small {
                text(
                    "Intervals are 95%, bootstrapped over problems, a problem's seeds and repeats averaged. ",
                )
                text("PAR-2 charges an unsolved run twice its budget.")
                text(
                    " Checked witnesses establish feasible points. Eligible native proofs exclude contradicted optima and unavailable scope/checks; absence of a counterexample is not an independent proof.",
                )
                if (compared) {
                    text(
                        " The time ratio is the geometric mean of an arm's PAR-2 time over the first arm's, on the problems either ",
                    )
                    text(
                        "solved; its p is a Wilcoxon signed-rank test. Equal results whose times differ by less than 0.25 s or 10% are a ",
                    )
                    text(
                        "tie. Better and worse count problems by the score, with a sign test. p below 0.05 is bold.",
                    )
                }
            }
        }
    }
    if (comparison.disagreements.isNotEmpty()) {
        h2(classes = "FAILED") {
            text("Disagreements ")
            small { emit(comparison.disagreements.size) }
        }
        ul(classes = "error") {
            for (d in comparison.disagreements) li {
                code { emit(name(d.problem)) }
                text(": ")
                emit(d.reason)
            }
        }
    }
    if (stats.noise.isNotEmpty()) emit(noiseTable(stats.noise))
    if (references.isNotEmpty() && !referenceRun) emit(referenceSection(labels, cases, references))
    emit(problemGrid(labels, cases, if (referenceRun) emptyMap() else references, compared))
}

/**
 * Each arm against the lab's reference results: who solved what, optima, gaps, and where they
 * contradict.
 */
private fun referenceSection(
    labels: List<String>,
    cases: List<CaseResult>,
    references: Map<ProblemKey, Reference>,
): Html = buildHtml {
    val comparison = References.compare(labels, cases, references)
    h2 {
        text("Against the reference ")
        small {
            emit(references.size)
            text(" of ")
            emit(cases.map { it.problem }.distinct().size)
            text(" problems have ")
            text("a reference verdict · ")
            a(href = "/references") {
                text("reference results")
            }
        }
    }
    div(classes = "scroll") {
        table {
            tr {
                th { text("arm") }
                th(classes = "num") {
                    text("covered")
                }
                th(classes = "num") {
                    text("both solved")
                }
                th(classes = "num") {
                    text("only the arm")
                }
                th(classes = "num") {
                    text("only the reference")
                }
                th(classes = "num") {
                    text("proven optima reached")
                }
                th(classes = "num") {
                    text("beats its best")
                }
                th(classes = "num") {
                    text("worse incumbent")
                }
                th(classes = "num") {
                    text("mean gap")
                }
            }
            for (r in comparison.summaries) {
                tr {
                    td { b { emit(r.arm) } }
                    td(classes = "num") {
                        emit(r.covered)
                    }
                    td(classes = "num") {
                        emit(r.bothSolved)
                    }
                    td(classes = "num") {
                        emit(
                            if (r.armOnly > 0)
                                buildHtml {
                                    span(classes = "DONE") {
                                        emit(r.armOnly)
                                    }
                                }
                            else "0",
                        )
                    }
                    td(classes = "num") {
                        emit(
                            if (r.referenceOnly > 0)
                                buildHtml {
                                    span(classes = "FAILED") {
                                        emit(r.referenceOnly)
                                    }
                                }
                            else "0",
                        )
                    }
                    td(classes = "num") {
                        emit(r.optimaMatched)
                        text("/")
                        emit(r.provenOptima)
                    }
                    td(classes = "num") {
                        emit(r.better)
                    }
                    td(classes = "num") {
                        emit(
                            if (r.worse > 0)
                                buildHtml {
                                    span(classes = "PARTIAL") {
                                        emit(r.worse)
                                    }
                                }
                            else "0",
                        )
                    }
                    td(classes = "num") {
                        emit(r.meanGap?.let { "%.1f%%".format(it * 100) } ?: "–")
                    }
                }
            }
        }
    }
    p(classes = "muted") {
        small {
            text("The reference ran under its own budget, so this compares verdicts ")
            text(
                "and objectives, not speed. Mean gap is the relative distance to the reference objective where the arm is worse.",
            )
        }
    }
    if (comparison.shortfalls.isNotEmpty()) {
        details {
            summary {
                b { text("Worse incumbents") }
                text(" ")
                small(classes = "muted") {
                    emit(comparison.shortfalls.size)
                    text(" problems where ")
                    text(
                        "an arm's best objective is worse than the reference's, the largest gap first",
                    )
                }
            }
            div(classes = "scroll") {
                table {
                    tr {
                        th { text("arm") }
                        th { text("problem") }
                        th(classes = "num") {
                            text("objective")
                        }
                        th(classes = "num") {
                            text("reference")
                        }
                        th(classes = "num") {
                            text("gap")
                        }
                    }
                    for (f in comparison.shortfalls) {
                        tr {
                            td { emit(f.arm) }
                            td {
                                a(
                                    classes = "plain",
                                    href =
                                        (problemLink(f.problem.collection, f.problem.problem))
                                            ,
                                ) {
                                    code { emit(name(f.problem)) }
                                }
                            }
                            td(classes = "num") {
                                emit(number(f.objective))
                            }
                            td(classes = "num") {
                                emit(number(f.reference))
                                emit(
                                    if (f.referenceProven)
                                        buildHtml {
                                            text(" ")
                                            small(classes = "muted") {
                                                text("proven")
                                            }
                                        }
                                    else "",
                                )
                            }
                            td(classes = "num") {
                                emit("%.1f%%".format(f.gap * 100))
                            }
                        }
                    }
                }
            }
        }
    }
    if (comparison.disagreements.isNotEmpty()) {
        h2(classes = "FAILED") {
            text("Disagreements with the reference ")
            small { emit(comparison.disagreements.size) }
        }
        ul(classes = "error") {
            for (d in comparison.disagreements) li {
                code { emit(name(d.problem)) }
                text(": ")
                emit(d.reason)
            }
        }
    }
}

private fun provenance(summary: ArmSummary): Html = buildHtml {
    if (
        summary.buildFingerprints.isEmpty() &&
            summary.validationPolicies.isEmpty() &&
            summary.missingProvenance == 0
    ) {
        span(classes = "muted") {
            text("pending")
        }
        return@buildHtml
    }
    if (summary.buildFingerprints.size > 1 || summary.validationPolicies.size > 1) {
        span(classes = "PARTIAL") {
            text("mixed provenance")
        }
        br {}
    }
    details(classes = "provenance") {
        attributes["id"] = "provenance-${URLEncoder.encode(summary.label, Charsets.UTF_8)}"
        summary {
            emit(summary.buildFingerprints.size)
            text(" builds · ")
            emit(summary.validationPolicies.size)
            text(" policies")
        }
        div {
            emit(
                summary.buildFingerprints.joinHtml(buildHtml { br {} }) {
                    buildHtml { code { emit(it) } }
                },
            )
            if (summary.validationPolicies.isNotEmpty()) {
                p {
                    emit(
                        summary.validationPolicies.joinHtml(buildHtml { br {} }) {
                            htmlText(it)
                        },
                    )
                }
            }
        }
    }
    if (summary.missingProvenance > 0) {
        br {}
        span(classes = "PARTIAL") {
            text("records missing provenance: ")
            emit(summary.missingProvenance)
        }
    }
}

/**
 * An arm against the baseline: the time ratio with its interval and test, then better and worse
 * with theirs.
 */
private fun versus(p: Paired): Html {
    val ratio =
        if (p.problems == 0)
            buildHtml {
                span(classes = "muted") {
                    text("no problem solved by either")
                }
            }
        else
            buildHtml {
                emit("%.2f".format(p.timeRatio.value))
                text("× ")
                small(classes = "muted") {
                    emit("%.2f".format(p.timeRatio.low))
                    text("–")
                    emit("%.2f".format(p.timeRatio.high))
                }
                text(" ")
                emit(pValue(p.wilcoxonP))
                text(" ")
                small(classes = "muted") {
                    text("n=")
                    emit(p.problems)
                }
            }
    return buildHtml {
        emit(ratio)
        br {}
        span(classes = "DONE") {
            emit(p.better)
            text(" better")
        }
        text(" · ")
        span(classes = "FAILED") {
            emit(p.worse)
            text(" worse")
        }
        text(" ")
        emit(pValue(p.signP))
    }
}

internal fun estimate(e: Estimate, format: String) = buildHtml {
    emit(format.format(e.value))
    text(" ")
    small(classes = "muted") {
        emit(format.format(e.low))
        text("–")
        emit(format.format(e.high))
    }
}

private fun pValue(p: Double): Html {
    val text = if (p < 0.001) buildHtml { text("p<0.001") } else "p=${"%.3f".format(p)}"
    return if (p < SIGNIFICANT) buildHtml { b { emit(text) } }
    else buildHtml { small { emit(text) } }
}

/** Problems whose runs of one arm disagree, the most unsettled first. */
private fun noiseTable(noise: List<Noise>): Html = buildHtml {
    details(classes = "result-diagnostics") {
        attributes["id"] = "noisy-problems"

        summary {
            b { text("Noisy problems") }
            text(" · ")
            emit(noise.size)
        }
        p(classes = "muted") {
            text(
                "Runs that disagree on the verdict, or whose time to best spreads over 25% of its mean.",
            )
        }
        div(classes = "scroll") {
            table {
                tr {
                    th { text("problem") }
                    th { text("arm") }
                    th(classes = "num") {
                        text("runs")
                    }
                    th(classes = "num") {
                        text("verdicts")
                    }
                    th(classes = "num") {
                        text("time spread")
                    }
                }
                for (n in noise.take(NOISE_ROWS)) {
                    tr {
                        td { code { emit(name(n.problem)) } }
                        td { emit(n.arm) }
                        td(classes = "num") {
                            emit(n.runs)
                        }
                        td(classes = "num") {
                            emit(
                                if (n.verdicts > 1)
                                    buildHtml {
                                        span(classes = "FAILED") {
                                            emit(n.verdicts)
                                        }
                                    }
                                else "1",
                            )
                        }
                        td(classes = "num") {
                            emit("%.0f".format(n.spread * 100))
                            text("%")
                        }
                    }
                }
            }
        }
        if (noise.size > NOISE_ROWS)
            p(classes = "muted") {
                emit(noise.size - NOISE_ROWS)
                text(" more in cases.csv")
            }
    }
}

private const val SIGNIFICANT = 0.05

private const val NOISE_ROWS = 50

/**
 * One row per problem, one cell per arm with its outcome on each seed; the arm that scores best on
 * a row is marked.
 */
private fun problemGrid(
    labels: List<String>,
    cases: List<CaseResult>,
    references: Map<ProblemKey, Reference> = emptyMap(),
    compared: Boolean = true,
): Html = buildHtml {
    val byProblem = cases.groupBy { it.problem }
    section {
        attributes["id"] = "problems"
        attributes["data-result-list"] = ""
        h2 {
            text("Problems ")
            small {
                emit(byProblem.size)
                text(" total")
            }
            if (compared) {
                text(" ")
                small {
                    label {
                        input {
                            attributes["type"] = "checkbox"
                            attributes["id"] = "differ"
                        }
                        text(" only where arms differ")
                    }
                }
            }
        }
        emit(resultFilters("problem"))
        div(classes = "scroll problem-scroll") {
            attributes["tabindex"] = "0"
            attributes["aria-label"] = "Problem results"
            table(classes = "grid") {
                tr {
                    th { text("problem") }
                    for (label in labels) th { emit(label) }
                    if (references.isNotEmpty())
                        th(classes = "ref") {
                            text("reference")
                        }
                }
                for ((problem, ofProblem) in byProblem) {
                    val outcomes =
                        ofProblem
                            .groupBy { it.arm }
                            .mapValues { (_, cs) -> cs.sortedBy { it.seed ?: Long.MIN_VALUE } }
                    val verdicts = labels.map { label ->
                        outcomes[label].orEmpty().map { verdict(Outcome.of(it.record), it.status) }
                    }
                    val points = labels.associateWith { label ->
                        ofProblem
                            .filter { it.arm == label }
                            .sumOf { mine ->
                                val a = Outcome.of(mine.record) ?: return@sumOf 0.0
                                ofProblem
                                    .filter {
                                        it.arm != label &&
                                            it.seed == mine.seed &&
                                            it.repeat == mine.repeat
                                    }
                                    .sumOf { other ->
                                        Outcome.of(other.record)?.let { Compare.points(a, it) }
                                            ?: 0.0
                                    }
                            }
                    }
                    val best =
                        points.values.maxOrNull()?.takeIf { top -> points.values.any { it < top } }
                    val label = buildHtml { code { emit(name(problem)) } }
                    val cell =
                        if (problem.collection.isEmpty()) label
                        else
                            buildHtml {
                                a(
                                    classes = "plain",
                                    href =
                                        (problemLink(problem.collection, problem.problem))
                                            ,
                                ) {
                                    emit(label)
                                }
                            }
                    tr {
                        attributes["data-result-row"] = ""
                        attributes["data-search"] = (name(problem))
                        attributes["data-differ"] = (verdicts.distinct().size > 1).toString()
                        td { emit(cell) }
                        for (label in labels) {
                            td {
                                if (best != null && points[label] == best) classes = setOf("best")
                                emit(
                                    outcomes[label].orEmpty().joinHtml(buildHtml { br {} }) { case
                                        ->
                                        val outcome = Outcome.of(case.record)
                                        val time =
                                            outcome
                                                ?.takeIf { it.rank > 0 }
                                                ?.let {
                                                    buildHtml {
                                                        text(" ")
                                                        small(classes = "muted") {
                                                            emit(
                                                                "%.2f".format(it.timeMs / 1000.0),
                                                            )
                                                            text("s")
                                                        }
                                                    }
                                                }
                                                .orEmpty()
                                        verdict(outcome, case.status) + time
                                    },
                                )
                            }
                        }
                        if (references.isNotEmpty())
                            td(classes = "ref") {
                                emit(referenceVerdict(references[problem.referenceKey]))
                            }
                    }
                }
            }
        }
        p(classes = "muted") {
            attributes["data-result-count"] = ""
            attributes["aria-live"] = "polite"
        }
        p {
            attributes["data-result-empty"] = ""
            attributes["hidden"] = ""
            text("No matching problems.")
        }
    }
}

/**
 * A reference verdict in a word, the solver beside it and its time against its own budget on hover.
 */
internal fun referenceVerdict(r: Reference?): Html {
    if (r == null)
        return buildHtml {
            span(classes = "muted") {
                text("–")
            }
        }
    val word =
        when {
            r.feasible == false -> "infeasible"
            r.feasible == null -> "unknown"
            r.objective != null -> number(r.objective) + if (r.proven) "*" else ""
            else -> "sat"
        }
    val title =
        "${r.solver}: ${"%.2f".format(r.elapsedMs / 1000.0)}s of a ${r.budgetMs / 1000}s budget"
    return buildHtml {
        span {
            attributes["title"] = (title)
            emit(word)
        }
        text(" ")
        small(classes = "muted") {
            emit(r.solver)
        }
    }
}

/**
 * One outcome in a word: the objective (starred when proven), sat, infeasible, unknown,
 * unsupported, a load error, or the case's status when it left no record; a reason shows on hover.
 * A Solver solution claim carries how far its witness was checked ([witnessMark]).
 */
internal fun verdict(outcome: Outcome?, status: Status): Html =
    outcomeWord(outcome, status) + witnessMark(outcome)

private fun outcomeWord(outcome: Outcome?, status: Status): Html =
    when {
        outcome == null ->
            buildHtml {
                span(classes = (status.name)) {
                    emit(status.name.lowercase())
                }
            }
        outcome.error ->
            buildHtml {
                span(classes = "FAILED") {
                    text("error")
                }
            }
        outcome.unsupported != null ->
            buildHtml {
                span(classes = "PARTIAL") {
                    attributes["title"] = (outcome.unsupported)
                    text("unsupported")
                }
            }
        outcome.loadError != null ->
            buildHtml {
                span(classes = "FAILED") {
                    attributes["title"] = (outcome.loadError)
                    text("load error")
                }
            }
        SourceValidation.rejects(outcome.sourceValidation) ->
            buildHtml {
                span(classes = "FAILED") {
                    attributes["title"] = (outcome.sourceValidationReason.orEmpty())
                    text("invalid solution")
                }
            }
        outcome.floatApproximation && !outcome.accepted ->
            buildHtml {
                span(classes = "PARTIAL") {
                    attributes["title"] = (outcome.sourceValidationReason.orEmpty())
                    text("unchecked grid")
                }
            }
        outcome.floatApproximation && outcome.feasible == true ->
            buildHtml {
                span {
                    attributes["title"] = "source-checked grid witness; source optimality unproved"
                    emit(outcome.objective?.let(::number) ?: "sat")
                    text(" ")
                    small(classes = "muted") {
                        text("grid")
                    }
                }
            }
        outcome.feasible == false -> buildHtml { text("infeasible") }
        outcome.feasible == null ->
            buildHtml {
                span(classes = "muted") {
                    text("unknown")
                }
            }
        outcome.optimize && outcome.objective != null ->
            htmlText(number(outcome.objective) + if (outcome.proven) "*" else "")
        else -> buildHtml { text("sat") }
    }

/**
 * How far a Solver solution claim was checked against its original source, as a small word beside
 * its verdict: `checked` for a witness the source accepted, `unchecked` for the solver's own report
 * (with the reason when a check was asked for and its checker could not run), `check indeterminate`
 * for a check that could not decide, the reason and policy on hover. An accepted witness is a valid
 * solution; a proof of optimality beside it (the star) is still the solver's own claim. Nothing for
 * an outcome with no solution claim, for another solver's, or where the verdict already says it (an
 * invalid solution, a float grid).
 */
internal fun witnessMark(outcome: Outcome?): Html {
    if (
        outcome == null || outcome.error || outcome.unsupported != null || outcome.loadError != null
    )
        return buildHtml {}
    if (!outcome.claimedSolution || outcome.floatApproximation) return buildHtml {}
    val policy = outcome.validationPolicy?.let { " (policy ${it})" }.orEmpty()
    return when (outcome.witness) {
        WitnessCheck.ACCEPTED ->
            buildHtml {
                text(" ")
                small(classes = "DONE") {
                    attributes["title"] =
                        ("witness accepted against the original source${policy}: the solution is valid; a proof of optimality is the solver's own claim")

                    text("checked")
                }
            }
        WitnessCheck.UNCHECKED -> {
            val why =
                when (outcome.sourceValidation) {
                    SourceVerdict.UNKNOWN,
                    SourceVerdict.NOT_RUN ->
                        "the source checker could not run: ${outcome.sourceValidationReason}"
                    SourceVerdict.UNSUPPORTED ->
                        "no external checker covers it: ${outcome.sourceValidationReason}"
                    SourceVerdict.VALID,
                    SourceVerdict.INVALID,
                    null -> "no independent source check was asked for or performed"
                }
            buildHtml {
                text(" ")
                small(classes = "muted") {
                    attributes["title"] = ("the solver's own report${policy}: ${why}")
                    text("unchecked")
                }
            }
        }
        WitnessCheck.INDETERMINATE -> {
            val reported = buildList {
                add(
                    "reported ${(outcome.reportedObjective ?: outcome.objective?.let(::number))?.let { "objective ${it}" } ?: "a solution"}",
                )
                if (outcome.reportedProven || outcome.proven) add("proven")
            }
                .joinToString(", ")
            val reason = outcome.sourceValidationReason ?: "no result recorded"
            buildHtml {
                text(" ")
                small(classes = "PARTIAL") {
                    attributes["title"] =
                        ("the source check could not decide${policy}: ${reason}; ${reported}")

                    text("check indeterminate")
                }
            }
        }
        WitnessCheck.INVALID,
        null -> buildHtml {}
    }
}

/**
 * An arm's witness checks in a word beside its label, the counts on hover; nothing when it claimed
 * no solution.
 */
private fun witnessLabel(summary: ArmSummary): Html {
    val counts = summary.witnesses
    if (counts.isEmpty()) return buildHtml {}
    val title =
        (WitnessCheck.entries
            .filter { (counts[it] ?: 0) > 0 }
            .joinToString(", ") { "${counts[it]} ${it.word}" } +
            " Solver solutions; a checked witness is a valid solution, not a checked proof")
    return when {
        (counts[WitnessCheck.ACCEPTED] ?: 0) > 0 || (counts[WitnessCheck.INDETERMINATE] ?: 0) > 0 ->
            buildHtml {
                text(" ")
                span(classes = "DONE") {
                    attributes["title"] = (title)
                    text("source-checked")
                }
            }
        else ->
            buildHtml {
                text(" ")
                span(classes = "muted") {
                    attributes["title"] = (title)
                    text("unchecked")
                }
            }
    }
}

private fun describe(arm: Arm) =
    arm.values.toSortedMap().entries.joinToString(" ") { (k, v) -> "${k}=${v}" }
