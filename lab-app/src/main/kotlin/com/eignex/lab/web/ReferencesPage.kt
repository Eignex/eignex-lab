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
import kotlinx.html.*

/** The reference tab: what the lab's reference results cover, and a filtered search over them. */
internal fun referencesPage(
    coverage: List<ReferenceCoverage>,
    filter: ReferenceFilter,
    found: List<Pair<ProblemKey, Reference>>,
    total: Int,
    /** Problems two reference solvers contradict each other on. */
    conflicts: List<Disagreement> = emptyList(),
    /** A candidate run against the references, which the tab leads with. */
    scoreboard: ScoreboardView? = null,
): Html =
    page("reference · eignex lab", tab = Tab.REFERENCE, script = PageScripts.reference) {
        val visible = coverage
        val context = scoreboard?.let(::scoreboardParameters).orEmpty()
        val contextQuery = query(*context.toList().toTypedArray())
        val coveragePrefix = "/references${if (contextQuery.isEmpty()) "?" else "$contextQuery&"}"
        header {
            h1 { text("Reference") }
            p(classes = "muted") {
                emit(visible.sumOf { it.rows })
                text(" results from ")
                emit(visible.map { it.solver }.distinct().size)
                text(" solvers over ")
                emit(visible.map { it.collection }.distinct().size)
                text(" collections")
            }
        }
        nav(classes = "queue-sections") {
            attributes["aria-label"] = "Reference sections"
            if (scoreboard != null)
                a(href = "#reference-comparison") {
                    text("Compare a run")
                }
            a(href = "#reference-search") {
                text("Search results")
            }
            a(href = "#reference-coverage") {
                text("Coverage")
            }
            if (conflicts.isNotEmpty())
                a(classes = "FAILED", href = "#reference-conflicts") {
                    text("Conflicts (")
                    emit(conflicts.size)
                    text(")")
                }
        }
        if (conflicts.isNotEmpty())
            p(classes = "reference-warning") {
                b {
                    emit(conflicts.size)
                    text(" problems have conflicting reference verdicts.")
                }
                text(" ")
                a(href = "#reference-conflicts") {
                    text("Inspect solver disagreements")
                }
                text(" when interpreting the comparison.")
            }
        scoreboard?.let {
            section {
                attributes["id"] = "reference-comparison"
                emit(scoreboardSection(it, filter))
            }
        }
        section {
            attributes["id"] = "reference-search"
            h2 { text("Search reference results") }
            p(classes = "muted small") {
                text(
                    "Find stored verdicts by problem, collection, solver, or verdict. These filters are separate from the run comparison above.",
                )
            }
            form(classes = "reference-form reference-search") {
                attributes["method"] = "get"
                attributes["action"] = "/references#reference-search"

                attributes["aria-label"] = "Search reference results"
                emit(hiddenParameters(context))
                label(classes = "problem-search") {
                    span { text("Problem or collection") }
                    input {
                        attributes["name"] = "q"
                        attributes["type"] = "search"
                        attributes["placeholder"] = "Search by name…"
                        attributes["value"] = (filter.text.orEmpty())
                    }
                }
                emit(
                    referenceSelect(
                        "Solver",
                        "solver",
                        "Every solver",
                        coverage.map { it.solver }.distinct().sorted().map { it to it },
                        filter.solver,
                    ),
                )
                emit(
                    referenceSelect(
                        "Collection",
                        "collection",
                        "Every collection",
                        visible.map { it.collection }.distinct().sorted().map { it to it },
                        filter.collection,
                    ),
                )
                emit(
                    referenceSelect(
                        "Verdict",
                        "verdict",
                        "Any verdict",
                        ReferenceVerdict.entries.map { it.name.lowercase() to it.label },
                        filter.verdict?.name?.lowercase(),
                    ),
                )
                button {
                    attributes["type"] = "submit"
                    text("Search results")
                }
                if (!filter.isEmpty)
                    a(
                        classes = "chip",
                        href =
                            "/references${query(*context.toList().toTypedArray())}#reference-search",
                    ) {
                        text("Clear search")
                    }
            }
            if (!filter.isEmpty) {
                h2 {
                    text("Results ")
                    small {
                        emit(
                            if (total > found.size) "${found.size} of ${total}" else "${total}",
                        )
                    }
                }
                if (found.isEmpty())
                    p(classes = "empty-state") {
                        text(
                            "No reference results match these filters. Try another search or clear the search filters.",
                        )
                    }
                if (total > found.size)
                    p(classes = "muted small") {
                        text("Showing the first ")
                        emit(found.size)
                        text(" results. Refine the search to see a smaller set.")
                    }
                div(classes = "scroll") {
                    attributes["role"] = "region"
                    attributes["aria-label"] = "Reference search results"
                    attributes["tabindex"] = "0"
                    table(classes = "reference-results") {
                        thead {
                            tr {
                                th {
                                    attributes["scope"] = "col"
                                    text("Collection")
                                }
                                th {
                                    attributes["scope"] = "col"
                                    text("Problem")
                                }
                                th {
                                    attributes["scope"] = "col"
                                    text("Solver")
                                }
                                th {
                                    attributes["scope"] = "col"
                                    text("Verdict")
                                }
                                th(classes = "num") {
                                    attributes["scope"] = "col"

                                    text("Time")
                                }
                                th(classes = "num") {
                                    attributes["scope"] = "col"

                                    text("Budget")
                                }
                            }
                        }
                        tbody {
                            for ((key, r) in found) {
                                tr {
                                    td { emit(key.collection) }
                                    td {
                                        a(
                                            classes = "plain",
                                            href =
                                                (problemLink(key.collection, key.problem))
                                                    ,
                                        ) {
                                            code { emit(key.problem) }
                                        }
                                    }
                                    td { emit(r.solver) }
                                    td { emit(referenceVerdict(r)) }
                                    td(classes = "num") {
                                        emit("%.2f".format(r.elapsedMs / 1000.0))
                                        text("s")
                                    }
                                    td(classes = "num") {
                                        emit(r.budgetMs / 1000)
                                        text("s")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (conflicts.isNotEmpty()) {
            section {
                attributes["id"] = "reference-conflicts"
                details(classes = "reference-conflicts") {
                    attributes["data-open-on-hash"] = "reference-conflicts"
                    summary {
                        b { text("Conflicting reference verdicts") }
                        text(" · ")
                        emit(conflicts.size)
                        text(" problems")
                    }
                    p(classes = "muted small") {
                        text(
                            "These solvers report contradictory results. Open a problem to inspect the evidence.",
                        )
                    }
                    ul(classes = "error") {
                        for (d in conflicts) {
                            li {
                                a(
                                    classes = "plain",
                                    href =
                                        (problemLink(d.problem.collection, d.problem.problem))
                                            ,
                                ) {
                                    code {
                                        emit(d.problem.collection)
                                        text("/")
                                        emit(d.problem.problem)
                                    }
                                }
                                text(": ")
                                emit(d.reason)
                            }
                        }
                    }
                }
            }
        }
        val shown = visible.filter { c ->
            (filter.solver == null || c.solver == filter.solver) &&
                (filter.collection == null || c.collection == filter.collection)
        }
        section {
            attributes["id"] = "reference-coverage"
            h2 {
                text("Reference coverage")
                emit(
                    if (shown.size < visible.size)
                        buildHtml {
                            text(" ")
                            small {
                                emit(shown.size)
                                text(" of ")
                                emit(visible.size)
                                text(" rows")
                            }
                        }
                    else "",
                )
            }
            if (coverage.isEmpty())
                p(classes = "muted") {
                    text("No reference results yet.")
                }
            p(classes = "muted small") {
                text(
                    "One row per collection and solver; counts may include the same problem for different solvers. Coverage follows the solver and collection filters, regardless of search text or verdict.",
                )
            }
            div(classes = "scroll") {
                attributes["role"] = "region"
                attributes["aria-label"] = "Reference coverage"
                attributes["tabindex"] = "0"
                table(classes = "coverage-table") {
                    thead {
                        tr {
                            th {
                                attributes["scope"] = "col"
                                text("Collection")
                            }
                            th {
                                attributes["scope"] = "col"
                                text("Solver")
                            }
                            th(classes = "num") {
                                attributes["scope"] = "col"

                                text("Results")
                            }
                            th(classes = "num") {
                                attributes["scope"] = "col"

                                text("Decided")
                            }
                            th(classes = "num") {
                                attributes["scope"] = "col"

                                text("Proven")
                            }
                            th(classes = "num") {
                                attributes["scope"] = "col"

                                text("Infeasible")
                            }
                            th {
                                attributes["scope"] = "col"
                                text("Updated")
                            }
                        }
                    }
                    tbody {
                        // A family of collections reads as one row until it is opened; each family
                        // renders as a block where its first
                        // member sorts, with its members right under it.
                        val families = shown.groupBy { c ->
                            FAMILIES.firstOrNull { (_, member) -> member(c.collection) }?.first
                        }
                        val drawn = HashSet<String>()
                        for (c in shown) {
                            val family =
                                FAMILIES.firstOrNull { (_, member) -> member(c.collection) }?.first
                            val members = families[family].orEmpty()
                            if (family == null || members.size < 2) {
                                tr { emit(coverageCells(c, coveragePrefix)) }
                                continue
                            }
                            if (!drawn.add(family)) continue
                            tr(classes = "family") {
                                td {
                                    button(classes = "coverage-toggle") {
                                        attributes["type"] = "button"

                                        attributes["data-family"] = (family)
                                        attributes["aria-expanded"] = "false"
                                        span {
                                            attributes["data-family-icon"] = ""
                                            attributes["aria-hidden"] = "true"
                                            text("▸")
                                        }
                                        text(" ")
                                        emit(family)
                                        text(" ")
                                        small(classes = "muted") {
                                            emit(members.map { it.collection }.distinct().size)
                                            text(" collections · ")
                                            emit(members.size)
                                            text(" solver rows")
                                        }
                                    }
                                }
                                td { emit(members.map { it.solver }.distinct().joinToString()) }
                                td(classes = "num") {
                                    emit(members.sumOf { it.rows })
                                }
                                td(classes = "num") {
                                    emit(members.sumOf { it.decided })
                                }
                                td(classes = "num") {
                                    emit(members.sumOf { it.proven })
                                }
                                td(classes = "num") {
                                    emit(members.sumOf { it.infeasible })
                                }
                                td { emit(ago(members.maxOf { it.updatedAt })) }
                            }
                            for (member in members) tr {
                                attributes["data-in"] = (family)
                                attributes["hidden"] = ""
                                emit(coverageCells(member, coveragePrefix))
                            }
                        }
                    }
                }
            }
        }
    }

/**
 * A candidate run against every reference solver: a row per group, a cell per solver, each cell
 * candidate's Borda share.
 */
private fun scoreboardSection(view: ScoreboardView, search: ReferenceFilter): Html = buildHtml {
    h2 { text("Compare a run with reference solvers") }
    p(classes = "muted small") {
        text(
            "Read the selected run's share of comparison points against each reference solver. Higher is better; this is a point share, not a win rate.",
        )
    }
    val job = view.job
    if (job == null) {
        p(classes = "muted") {
            text("No finished scheduled run to score yet.")
        }
        return@buildHtml
    }
    val linkContext = scoreboardParameters(view) + searchParameters(search)
    fun link(vararg changes: Pair<String, String?>): String {
        val current = linkContext.toMutableMap()
        for ((k, v) in changes) current[k] = v
        return "/references${query(*current.entries.map { it.key to it.value }.toTypedArray())}"
    }
    form(classes = "reference-form comparison-form") {
        attributes["method"] = "get"
        attributes["action"] = "/references#reference-comparison"

        attributes["aria-label"] = "Run comparison settings"
        emit(hiddenParameters(searchParameters(search)))
        val runChoices = (view.runs + job).distinctBy { it.id }.map { it.id.toString() to it.name }
        emit(
            referenceSelect(
                "Candidate run",
                "job",
                "Select a run",
                runChoices,
                job.id.toString(),
            ),
        )
        if (view.arms.size > 1)
            emit(
                referenceSelect(
                    "Candidate arm",
                    "arm",
                    "Select an arm",
                    view.arms.map { it to it },
                    view.arm,
                ),
            )
        else emit(hiddenParameters(mapOf("arm" to view.arm)))
        emit(
            referenceSelect(
                "Group rows by",
                "by",
                "Select grouping",
                Dimension.entries.map {
                    it.name.lowercase() to it.label.replaceFirstChar { c -> c.uppercase() }
                },
                view.by.name.lowercase(),
            ),
        )
        emit(
            referenceSelect(
                "Scoring mode",
                "scoring",
                "Proofs first (default)",
                listOf("incomplete" to "Solutions only (local search)"),
                "incomplete".takeIf { view.scoring == Scoring.INCOMPLETE },
            ),
        )
        button {
            attributes["type"] = "submit"
            text("Update comparison")
        }
        val dimensions =
            Dimension.entries.filter { view.options[it].orEmpty().size > 1 || it in view.filters }
        if (dimensions.isNotEmpty()) {
            details(classes = "comparison-filters") {
                if (view.filters.isNotEmpty()) attributes["open"] = ""
                summary {
                    +"Filter comparison problems"
                    if (view.filters.isNotEmpty()) +" (${view.filters.size} active)"
                }
                div(classes = "reference-filter-fields") {
                    for (d in dimensions) {
                        val values =
                            (view.options[d].orEmpty() + listOfNotNull(view.filters[d])).distinct()
                        emit(
                            referenceSelect(
                                d.label.replaceFirstChar { it.uppercase() },
                                d.name.lowercase(),
                                "Any ${d.label}",
                                values.map { it to it },
                                view.filters[d],
                            ),
                        )
                    }
                }
            }
        }
        if (view.filters.isNotEmpty())
            a(
                classes = "chip",
                href =
                    (link(
                            *Dimension.entries.map { it.name.lowercase() to null }.toTypedArray(),
                        ))
                        ,
            ) {
                text("clear filters")
            }
    }
    p(classes = "muted small") {
        text("Selected run: ")
        a(href = "/jobs/${job.id}") {
            emit(job.name)
            text(" (#")
            emit(job.id)
            text(")")
        }
    }
    details(classes = "reference-help") {
        summary { text("How scoring and validation work") }
        p {
            text("Pairwise Borda scoring follows the MiniZinc Challenge rule: ")
            emit(
                if (view.scoring == Scoring.INCOMPLETE)
                    "local-search class, proofs ignored and equal results split evenly."
                else
                    "complete class, proofs first and equal results split by time when the run had the reference's budget on one core.",
            )
        }
        p {
            text(
                "Best reference uses the strongest trusted reference verdict on each problem. For relative speed, run a lab a/b experiment with both solvers as arms at the same budget.",
            )
        }
    }
    val board = view.board ?: return@buildHtml
    if (board.total.problems == 0) {
        p(classes = "muted") {
            text("None of this run's problems has a reference verdict")
            emit(if (view.filters.isEmpty()) "" else " under these filters")
            text(".")
        }
        return@buildHtml
    }
    if (view.witnesses.isNotEmpty()) {
        val counts =
            WitnessCheck.entries
                .filter { (view.witnesses[it] ?: 0) > 0 }
                .joinToString(", ") { "${view.witnesses[it]} ${it.word}" }
        p(classes = "muted") {
            text("Solver's solutions in this run: ")
            emit(counts)
            text(". ")
            if ((view.witnesses[WitnessCheck.UNCHECKED] ?: 0) > 0) {
                text(
                    "An unchecked solution is the solver's own report and scores as a source-checked one does. ",
                )
            }
            text("A checked witness makes the solution valid, not its proof.")
        }
    }
    div(classes = "score-legend") {
        attributes["aria-label"] = "How to read the scores"
        span(classes = "FAILED") {
            text("Below 50%: fewer points")
        }
        span { text("50%: equal share") }
        span(classes = "DONE") {
            text("Above 50%: more points")
        }
    }
    p(classes = "muted small") {
        text(
            "Each cell shows point share, shared problem count, and candidate vs reference time budgets. Open a score to inspect the problems, candidate losses first.",
        )
    }
    if (
        (listOf(board.total) + board.rows).any { row -> row.cells.values.any { it.budgetsDiffer } }
    ) {
        p(classes = "reference-budget-note") {
            b { text("Some time budgets differ.") }
            text(
                " Marked cells compare result quality with no correction for the extra time. They do not measure relative speed.",
            )
        }
    }
    div(classes = "scroll") {
        attributes["role"] = "region"
        attributes["aria-label"] = "Candidate point shares against reference solvers"
        attributes["tabindex"] = "0"
        table(classes = "board reference-board") {
            thead {
                tr {
                    th {
                        attributes["scope"] = "col"
                        emit(view.by.label.replaceFirstChar { it.uppercase() })
                    }
                    th(classes = "num") {
                        attributes["scope"] = "col"

                        text("Problems")
                    }
                    for (solver in board.solvers) th(classes = "num") {
                        attributes["scope"] = "col"

                        emit(if (solver == Scoreboard.BEST) "Best reference" else solver)
                    }
                }
            }
            tbody {
                for (row in listOf(board.total) + board.rows) {
                    tr {
                        if (row === board.total) classes = setOf("total")
                        td { emit(if (row === board.total) "All compared problems" else row.group) }
                        td(classes = "num") {
                            emit(row.problems)
                        }
                        for (solver in board.solvers) emit(
                            scoreCell(
                                row.cells[solver],
                                link("group" to row.group, "against" to solver) +
                                    "#comparison-details",
                            ),
                        )
                    }
                }
            }
        }
    }
    details(classes = "reference-help") {
        summary { text("Grouping and budget details") }
        p(classes = "muted") {
            text(
                "Each cell is candidate's share of the points against that solver over the problems both have a ",
            )
            text("result for; green above one half, red below. ")
            i { text("best") }
            text(" takes the strongest trusted reference verdict on each problem. ")
            if (view.by == Dimension.CLASS)
                text(
                    "The class is candidate's own profile of the model; a run that reported none takes one from the source's logic or the pipeline that solved it. ",
                )
            text("Open a cell for its problems, candidate's losses first.")
        }
        p(classes = "muted") {
            text(
                "A cell reads candidate's budget against the reference's. The score measures quality against stored ",
            )
            text(
                "reference verdicts: time splits an equal result only where the budgets are equal and candidate ran on one core, and a cell ",
            )
            text("marked ")
            span(classes = "PARTIAL") {
                text("Budgets differ")
            }
            text(" has problems whose reference had another budget, which it does not ")
            text(
                "correct for. For relative speed, run a lab a/b experiment with both solvers as arms at the same budget.",
            )
        }
    }
    val (group, against) = view.cell ?: return@buildHtml
    section {
        attributes["id"] = "comparison-details"
        h3 {
            emit(group)
            text(" against ")
            emit(against)
            text(" ")
            small {
                emit(view.problems.size)
                text(" problems · ")
                a(
                    href =
                        (link("group" to null, "against" to null)) +
                            "#reference-comparison",
                ) {
                    text("Close details")
                }
            }
        }
        div(classes = "scroll") {
            attributes["role"] = "region"
            attributes["aria-label"] = "Comparison problem details"
            attributes["tabindex"] = "0"
            table(classes = "score-details") {
                thead {
                    tr {
                        th {
                            attributes["scope"] = "col"
                            text("Problem")
                        }
                        th(classes = "num") {
                            attributes["scope"] = "col"

                            text("Candidate points")
                        }
                        th(classes = "num") {
                            attributes["scope"] = "col"

                            emit(against)
                            text(" points")
                        }
                        th(classes = "num") {
                            attributes["scope"] = "col"

                            text("Candidate vs reference budget")
                        }
                        th {
                            attributes["scope"] = "col"
                            text("Time used to split ties?")
                        }
                    }
                }
                tbody {
                    for (p in view.problems) {
                        val cls =
                            when {
                                p.candidate > p.reference -> "DONE"
                                p.candidate < p.reference -> "FAILED"
                                else -> "muted"
                            }
                        tr {
                            td {
                                a(
                                    classes = "plain",
                                    href =
                                        (problemLink(p.problem.collection, p.problem.problem))
                                            ,
                                ) {
                                    code {
                                        emit(p.problem.collection)
                                        text("/")
                                        emit(p.problem.problem)
                                    }
                                }
                            }
                            td(classes = "num ${cls}") {
                                emit("%.2f".format(p.candidate))
                            }
                            td(classes = "num") {
                                emit("%.2f".format(p.reference))
                            }
                            td(classes = "num") {
                                emit(Scoreboard.seconds(p.budgetMs))
                                text(" vs ")
                                emit(Scoreboard.seconds(p.referenceBudgetMs))
                            }
                            val differ = p.budgetMs != p.referenceBudgetMs
                            td(classes = (if (differ) "PARTIAL" else "muted")) {
                                emit(if (p.timed) "yes" else "no, ${p.untimed.orEmpty()}")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One scoreboard cell: candidate's share, tinted green above one half and red below, the stronger
 * the further off, then candidate's budget against the reference's (a range when its problems had
 * several) and a quality-only mark when they differ.
 */
private fun scoreCell(cell: ScoreCell?, href: String): Html {
    val share =
        cell?.share
            ?: return buildHtml {
                td(classes = "num muted no-score") {
                    small {
                        emit(
                            if (cell == null || cell.problems == 0) "No shared results"
                            else "No points awarded",
                        )
                    }
                }
            }
    val strength = (kotlin.math.abs(share - HALF_SHARE) * 2 * MAX_TINT).toInt()
    val tint =
        "background:color-mix(in srgb, var(--${if (share >= HALF_SHARE) "ok" else "bad"}) ${strength}%, transparent)"
    val perBudget =
        cell.referenceBudgets.entries.joinToString(", ") { (ms, n) ->
            "${Scoreboard.seconds(ms)} on ${n}"
        }
    return buildHtml {
        td(classes = "num") {
            style = tint
            a(href = href, classes = "plain") {
                title =
                    "candidate ${"%.1f".format(cell.candidate)} against ${"%.1f".format(cell.reference)} over ${cell.problems} problems${if (cell.referenceBudgets.size > 1) "; reference budgets: $perBudget"
                        else ""}"
                strong(classes = "score-value") { +"${"%.1f".format(share * 100)}%" }
                small(classes = "score-count muted") { +"${cell.problems} shared problems" }
                budgets(cell)?.let {
                    small(classes = "score-budget muted") {
                        attributes["title"] = "Candidate budget vs reference budget"
                        emit(it)
                    }
                }
            }
            if (cell.budgetsDiffer)
                small(classes = "budget-flag PARTIAL") {
                    attributes["title"] = (qualityOnly(cell))
                    text("Budgets differ")
                }
        }
    }
}

/**
 * A cell's budgets: candidate's against the reference's, or the range of the reference's when they
 * vary.
 */
internal fun budgets(cell: ScoreCell): String? {
    val mine = cell.budgetMs ?: return null
    val theirs = cell.referenceBudgets.keys.takeIf { it.isNotEmpty() } ?: return null
    val range =
        if (theirs.size == 1) Scoreboard.seconds(theirs.first())
        else
            "${Scoreboard.seconds(theirs.min()).removeSuffix(" s")}–${Scoreboard.seconds(theirs.max())}"
    return "${Scoreboard.seconds(mine)} vs ${range}"
}

/**
 * Why a cell whose budgets differ compares quality alone, and which way the reference's budget
 * leans.
 */
private fun qualityOnly(cell: ScoreCell): String {
    val mine = cell.budgetMs ?: 0
    val other = cell.referenceBudgets.filterKeys { it != mine }
    val lean =
        when {
            other.keys.all { it > mine } ->
                "the reference had a longer budget, so its proofs and objectives had more time"
            other.keys.all { it < mine } ->
                "the reference had a shorter budget, so candidate's proofs and objectives had more time"
            else -> "the reference had longer budgets on some and shorter on others"
        }
    return "Time splits no equal result on the ${other.values.sum()} of ${cell.problems} problems whose reference verdict had another budget than candidate's ${Scoreboard.seconds(mine)}, and ${lean}. The score compares quality, not speed."
}

private const val HALF_SHARE = 0.5

private const val MAX_TINT = 18

/**
 * Families of collections that coverage folds into one expandable row: a name and which collections
 * it holds.
 */
private val FAMILIES: List<Pair<String, (String) -> Boolean>> =
    listOf(
        "satlib" to { it.startsWith("satlib-") },
        "qfnra" to { collection -> collection == "smtlib-qf_nra" },
        "smtlib" to { it.startsWith("smtlib-") },
        "pb" to { it.startsWith("pb-") || it.startsWith("pb07-") },
        "orlib" to { it.startsWith("orlib-") },
        "miplib" to { it.startsWith("miplib") },
    )

/** One collection's coverage cells: the collection (linked to its results), solver, and counts. */
private fun coverageCells(c: ReferenceCoverage, prefix: String): Html = buildHtml {
    td {
        a(
            href =
                prefix +
                    query("collection" to c.collection, "solver" to c.solver).removePrefix("?") +
                    "#reference-search",
            classes = "plain",
        ) {
            +c.collection
        }
    }
    td { +c.solver }
    td(classes = "num") { +c.rows.toString() }
    td(classes = "num") { +c.decided.toString() }
    td(classes = "num") { +c.proven.toString() }
    td(classes = "num") { +c.infeasible.toString() }
    td { emit(ago(c.updatedAt)) }
}

/** Explicit submission lets an operator choose several filters before reloading. */
private fun referenceSelect(
    label: String,
    name: String,
    any: String,
    options: List<Pair<String, String>>,
    chosen: String?,
): Html = buildHtml {
    label {
        span { emit(label) }
        emit(select(name, any, options, chosen, submitOnChange = false))
    }
}

private fun searchParameters(filter: ReferenceFilter): Map<String, String?> =
    linkedMapOf(
        "q" to filter.text,
        "solver" to filter.solver,
        "collection" to filter.collection,
        "verdict" to filter.verdict?.name?.lowercase(),
    )

private fun scoreboardParameters(view: ScoreboardView): Map<String, String?> =
    linkedMapOf(
            "job" to view.job?.id?.toString(),
            "arm" to view.arm,
            "by" to view.by.name.lowercase(),
            "scoring" to "incomplete".takeIf { view.scoring == Scoring.INCOMPLETE },
            "group" to view.cell?.first,
            "against" to view.cell?.second,
        )
        .apply { for ((dimension, value) in view.filters) put(dimension.name.lowercase(), value) }

private fun hiddenParameters(params: Map<String, String?>): Html =
    params.entries
        .filter { it.value != null }
        .joinHtml {
            buildHtml {
                input {
                    attributes["type"] = "hidden"
                    attributes["name"] = (it.key)
                    attributes["value"] = (it.value.orEmpty())
                }
            }
        }
