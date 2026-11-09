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
): Html = page("reference · eignex lab", tab = Tab.REFERENCE, script = PageScripts.reference) {
    val visible = coverage
    val context = scoreboard?.let(::scoreboardParameters).orEmpty()
    val contextQuery = query(*context.toList().toTypedArray())
    val coveragePrefix = esc("/references" + if (contextQuery.isEmpty()) "?" else "$contextQuery&")
    append(Html("<header><h1>Reference</h1><p class=\"muted\">") + (visible.sumOf { it.rows }) + Html(" results from "))
    append(Html("") + (visible.map { it.solver }.distinct().size) + Html(" solvers over ") + (visible.map { it.collection }.distinct().size) + Html(" collections"))
    append(Html("</p></header>"))
    append(Html("<nav class=\"queue-sections\" aria-label=\"Reference sections\">"))
    if (scoreboard != null) append(Html("<a href=\"#reference-comparison\">Compare a run</a>"))
    append(Html("<a href=\"#reference-search\">Search results</a><a href=\"#reference-coverage\">Coverage</a>"))
    if (conflicts.isNotEmpty()) append(Html("<a class=\"FAILED\" href=\"#reference-conflicts\">Conflicts (") + conflicts.size + Html(")</a>"))
    append(Html("</nav>"))
    if (conflicts.isNotEmpty()) append(Html("<p class=\"reference-warning\"><b>") + conflicts.size + Html(" problems have conflicting reference verdicts.</b> <a href=\"#reference-conflicts\">Inspect solver disagreements</a> when interpreting the comparison.</p>"))
    scoreboard?.let {
        append(Html("<section id=\"reference-comparison\">"))
        append(scoreboardSection(it, filter))
        append(Html("</section>"))
    }
    append(Html("<section id=\"reference-search\"><h2>Search reference results</h2><p class=\"muted small\">Find stored verdicts by problem, collection, solver, or verdict. These filters are separate from the run comparison above.</p>"))
    append(Html("<form method=\"get\" action=\"/references#reference-search\" class=\"reference-form reference-search\" aria-label=\"Search reference results\">"))
    append(hiddenParameters(context))
    append(Html("<label class=\"problem-search\"><span>Problem or collection</span><input name=\"q\" type=\"search\" placeholder=\"Search by name…\" value=\"") + esc(filter.text.orEmpty()) + Html("\"></label>"))
    append(referenceSelect("Solver", "solver", "Every solver", coverage.map { it.solver }.distinct().sorted().map { it to it }, filter.solver))
    append(referenceSelect("Collection", "collection", "Every collection", visible.map { it.collection }.distinct().sorted().map { it to it }, filter.collection))
    append(referenceSelect("Verdict", "verdict", "Any verdict", ReferenceVerdict.entries.map { it.name.lowercase() to it.label }, filter.verdict?.name?.lowercase()))
    append(Html("<button type=\"submit\">Search results</button>"))
    if (!filter.isEmpty) append(Html("<a class=\"chip\" href=\"/references") + query(*context.toList().toTypedArray()) + Html("#reference-search\">Clear search</a>"))
    append(Html("</form>"))
    if (!filter.isEmpty) {
        append(Html("<h2>Results <small>") + (if (total > found.size) "${found.size} of ${total}" else "${total}") + Html("</small></h2>"))
        if (found.isEmpty()) append(Html("<p class=\"empty-state\">No reference results match these filters. Try another search or clear the search filters.</p>"))
        if (total > found.size) append(Html("<p class=\"muted small\">Showing the first ") + found.size + Html(" results. Refine the search to see a smaller set.</p>"))
        append(Html("<div class=\"scroll\" role=\"region\" aria-label=\"Reference search results\" tabindex=\"0\"><table class=\"reference-results\"><thead><tr><th scope=\"col\">Collection</th><th scope=\"col\">Problem</th><th scope=\"col\">Solver</th><th scope=\"col\">Verdict</th><th scope=\"col\" class=\"num\">Time</th>"))
        append(Html("<th scope=\"col\" class=\"num\">Budget</th></tr></thead><tbody>"))
        for ((key, r) in found) {
            append(Html("<tr><td>") + esc(key.collection) + Html("</td><td><a class=\"plain\" href=\"") + problemLink(key.collection, key.problem) + Html("\"><code>") + esc(key.problem) + Html("</code></a></td>"))
            append(Html("<td>") + esc(r.solver) + Html("</td><td>") + referenceVerdict(r) + Html("</td>"))
            append(Html("<td class=\"num\">") + ("%.2f".format(r.elapsedMs / 1000.0)) + Html("s</td><td class=\"num\">") + (r.budgetMs / 1000) + Html("s</td></tr>"))
        }
        append(Html("</tbody></table></div>"))
    }
    append(Html("</section>"))
    if (conflicts.isNotEmpty()) {
        append(Html("<section id=\"reference-conflicts\"><details class=\"reference-conflicts\" data-open-on-hash=\"reference-conflicts\"><summary><b>Conflicting reference verdicts</b> · ") + conflicts.size + Html(" problems</summary><p class=\"muted small\">These solvers report contradictory results. Open a problem to inspect the evidence.</p><ul class=\"error\">"))
        for (d in conflicts) {
            append(Html("<li><a class=\"plain\" href=\"") + problemLink(d.problem.collection, d.problem.problem) + Html("\">"))
            append(Html("<code>") + esc(d.problem.collection) + Html("/") + esc(d.problem.problem) + Html("</code></a>: ") + esc(d.reason) + Html("</li>"))
        }
        append(Html("</ul></details></section>"))
    }
    val shown = visible.filter { c ->
        (filter.solver == null || c.solver == filter.solver) && (filter.collection == null || c.collection == filter.collection)
    }
    append(Html("<section id=\"reference-coverage\"><h2>Reference coverage") + (if (shown.size < visible.size) (Html(" <small>") + shown.size + Html(" of ") + visible.size + Html(" rows</small>")) else "") + Html("</h2>"))
    if (coverage.isEmpty()) append(Html("<p class=\"muted\">No reference results yet.</p>"))
    append(Html("<p class=\"muted small\">One row per collection and solver; counts may include the same problem for different solvers. Coverage follows the solver and collection filters, regardless of search text or verdict.</p>"))
    append(Html("<div class=\"scroll\" role=\"region\" aria-label=\"Reference coverage\" tabindex=\"0\"><table class=\"coverage-table\"><thead><tr><th scope=\"col\">Collection</th><th scope=\"col\">Solver</th><th scope=\"col\" class=\"num\">Results</th>"))
    append(Html("<th scope=\"col\" class=\"num\">Decided</th><th scope=\"col\" class=\"num\">Proven</th><th scope=\"col\" class=\"num\">Infeasible</th><th scope=\"col\">Updated</th></tr></thead><tbody>"))
    // A family of collections reads as one row until it is opened; each family renders as a block where its first
    // member sorts, with its members right under it.
    val families = shown.groupBy { c -> FAMILIES.firstOrNull { (_, member) -> member(c.collection) }?.first }
    val drawn = HashSet<String>()
    for (c in shown) {
        val family = FAMILIES.firstOrNull { (_, member) -> member(c.collection) }?.first
        val members = families[family].orEmpty()
        if (family == null || members.size < 2) {
            append(Html("<tr>") + coverageCells(c, coveragePrefix) + Html("</tr>"))
            continue
        }
        if (!drawn.add(family)) continue
        append(Html("<tr class=\"family\"><td><button type=\"button\" class=\"coverage-toggle\" data-family=\"") + esc(family) + Html("\" aria-expanded=\"false\"><span data-family-icon aria-hidden=\"true\">▸</span> ") + esc(family) + Html(" <small class=\"muted\">") + members.map { it.collection }.distinct().size + Html(" collections · ") + members.size + Html(" solver rows</small></button></td>"))
        append(Html("<td>") + (esc(members.map { it.solver }.distinct().joinToString())) + Html("</td><td class=\"num\">") + (members.sumOf { it.rows }) + Html("</td>"))
        append(Html("<td class=\"num\">") + (members.sumOf { it.decided }) + Html("</td><td class=\"num\">") + (members.sumOf { it.proven }) + Html("</td>"))
        append(Html("<td class=\"num\">") + (members.sumOf { it.infeasible }) + Html("</td><td>") + ago(members.maxOf { it.updatedAt }) + Html("</td></tr>"))
        for (member in members) append(Html("<tr data-in=\"") + esc(family) + Html("\" hidden>") + coverageCells(member, coveragePrefix) + Html("</tr>"))
    }
    append(Html("</tbody></table></div></section>"))
}

/** A candidate run against every reference solver: a row per group, a cell per solver, each cell candidate's Borda share. */
private fun scoreboardSection(view: ScoreboardView, search: ReferenceFilter): Html = buildHtml {
    append(Html("<h2>Compare a run with reference solvers</h2><p class=\"muted small\">Read the selected run's share of comparison points against each reference solver. Higher is better; this is a point share, not a win rate.</p>"))
    val job = view.job
    if (job == null) {
        append(Html("<p class=\"muted\">No finished scheduled run to score yet.</p>"))
        return@buildHtml
    }
    val linkContext = scoreboardParameters(view) + searchParameters(search)
    fun link(vararg changes: Pair<String, String?>): String {
        val current = linkContext.toMutableMap()
        for ((k, v) in changes) current[k] = v
        return "/references${query(*current.entries.map { it.key to it.value }.toTypedArray())}"
    }
    append(Html("<form method=\"get\" action=\"/references#reference-comparison\" class=\"reference-form comparison-form\" aria-label=\"Run comparison settings\">"))
    append(hiddenParameters(searchParameters(search)))
    val runChoices = (view.runs + job).distinctBy { it.id }.map { it.id.toString() to it.name }
    append(referenceSelect("Candidate run", "job", "Select a run", runChoices, job.id.toString()))
    if (view.arms.size > 1) append(referenceSelect("Candidate arm", "arm", "Select an arm", view.arms.map { it to it }, view.arm))
    else append(hiddenParameters(mapOf("arm" to view.arm)))
    append(referenceSelect("Group rows by", "by", "Select grouping", Dimension.entries.map { it.name.lowercase() to it.label.replaceFirstChar { c -> c.uppercase() } }, view.by.name.lowercase()))
    append(referenceSelect("Scoring mode", "scoring", "Proofs first (default)", listOf("incomplete" to "Solutions only (local search)"), "incomplete".takeIf { view.scoring == Scoring.INCOMPLETE }))
    append(Html("<button type=\"submit\">Update comparison</button>"))
    val dimensions = Dimension.entries.filter { view.options[it].orEmpty().size > 1 || it in view.filters }
    if (dimensions.isNotEmpty()) {
        append(Html("<details class=\"comparison-filters\"") + (if (view.filters.isNotEmpty()) " open" else "") + Html("><summary>Filter comparison problems") +
            (if (view.filters.isNotEmpty()) " (${view.filters.size} active)" else "") + Html("</summary><div class=\"reference-filter-fields\">"))
        for (d in dimensions) {
            val values = (view.options[d].orEmpty() + listOfNotNull(view.filters[d])).distinct()
            append(referenceSelect(d.label.replaceFirstChar { it.uppercase() }, d.name.lowercase(), "Any ${d.label}", values.map { it to it }, view.filters[d]))
        }
        append(Html("</div></details>"))
    }
    if (view.filters.isNotEmpty()) append(Html("<a class=\"chip\" href=\"") + (link(*Dimension.entries.map { it.name.lowercase() to null }.toTypedArray())) + Html("\">clear filters</a>"))
    append(Html("</form><p class=\"muted small\">Selected run: <a href=\"/jobs/") + job.id + Html("\">") + esc(job.name) + Html(" (#") + job.id + Html(")</a></p>"))
    append(Html("<details class=\"reference-help\"><summary>How scoring and validation work</summary><p>Pairwise Borda scoring follows the MiniZinc Challenge rule: "))
    append(esc(if (view.scoring == Scoring.INCOMPLETE) "local-search class, proofs ignored and equal results split evenly."
        else "complete class, proofs first and equal results split by time when the run had the reference's budget on one core."))
    append(Html("</p><p>Best reference uses the strongest trusted reference verdict on each problem. For relative speed, run a lab a/b experiment with both solvers as arms at the same budget.</p></details>"))
    val board = view.board ?: return@buildHtml
    if (board.total.problems == 0) {
        append(Html("<p class=\"muted\">None of this run's problems has a reference verdict") + (if (view.filters.isEmpty()) "" else " under these filters") + Html(".</p>"))
        return@buildHtml
    }
    if (view.witnesses.isNotEmpty()) {
        val counts = WitnessCheck.entries.filter { (view.witnesses[it] ?: 0) > 0 }.joinToString(", ") { "${view.witnesses[it]} ${it.word}" }
        append(Html("<p class=\"muted\">Solver's solutions in this run: ") + counts + Html(". "))
        if ((view.witnesses[WitnessCheck.UNCHECKED] ?: 0) > 0) {
            append(Html("An unchecked solution is the solver's own report and scores as a source-checked one does. "))
        }
        append(Html("A checked witness makes the solution valid, not its proof.</p>"))
    }
    append(Html("<div class=\"score-legend\" aria-label=\"How to read the scores\"><span class=\"FAILED\">Below 50%: fewer points</span><span>50%: equal share</span><span class=\"DONE\">Above 50%: more points</span></div><p class=\"muted small\">Each cell shows point share, shared problem count, and candidate vs reference time budgets. Open a score to inspect the problems, candidate losses first.</p>"))
    if ((listOf(board.total) + board.rows).any { row -> row.cells.values.any { it.budgetsDiffer } }) {
        append(Html("<p class=\"reference-budget-note\"><b>Some time budgets differ.</b> Marked cells compare result quality with no correction for the extra time. They do not measure relative speed.</p>"))
    }
    append(Html("<div class=\"scroll\" role=\"region\" aria-label=\"Candidate point shares against reference solvers\" tabindex=\"0\"><table class=\"board reference-board\"><thead><tr><th scope=\"col\">") + esc(view.by.label.replaceFirstChar { it.uppercase() }) + Html("</th><th scope=\"col\" class=\"num\">Problems</th>"))
    for (solver in board.solvers) append(Html("<th scope=\"col\" class=\"num\">") + esc(if (solver == Scoreboard.BEST) "Best reference" else solver) + Html("</th>"))
    append(Html("</tr></thead><tbody>"))
    for (row in listOf(board.total) + board.rows) {
        append(Html("<tr") + (if (row === board.total) Html(" class=\"total\"") else "") + Html("><td>") + esc(if (row === board.total) "All compared problems" else row.group) + Html("</td><td class=\"num\">") + row.problems + Html("</td>"))
        for (solver in board.solvers) append(scoreCell(row.cells[solver], link("group" to row.group, "against" to solver) + "#comparison-details"))
        append(Html("</tr>"))
    }
    append(Html("</tbody></table></div><details class=\"reference-help\"><summary>Grouping and budget details</summary>"))
    append(Html("<p class=\"muted\">Each cell is candidate's share of the points against that solver over the problems both have a "))
    append(Html("result for; green above one half, red below. <i>best</i> takes the strongest trusted reference verdict on each problem. "))
    if (view.by == Dimension.CLASS) append(Html("The class is candidate's own profile of the model; a run that reported none takes one from the source's logic or the pipeline that solved it. "))
    append(Html("Open a cell for its problems, candidate's losses first.</p>"))
    append(Html("<p class=\"muted\">A cell reads candidate's budget against the reference's. The score measures quality against stored "))
    append(Html("reference verdicts: time splits an equal result only where the budgets are equal and candidate ran on one core, and a cell "))
    append(Html("marked <span class=\"PARTIAL\">Budgets differ</span> has problems whose reference had another budget, which it does not "))
    append(Html("correct for. For relative speed, run a lab a/b experiment with both solvers as arms at the same budget.</p>"))
    append(Html("</details>"))
    val (group, against) = view.cell ?: return@buildHtml
    append(Html("<section id=\"comparison-details\"><h3>") + esc(group) + Html(" against ") + esc(against) + Html(" <small>") + view.problems.size + Html(" problems · "))
    append(Html("<a href=\"") + link("group" to null, "against" to null) + Html("#reference-comparison\">Close details</a></small></h3>"))
    append(Html("<div class=\"scroll\" role=\"region\" aria-label=\"Comparison problem details\" tabindex=\"0\"><table class=\"score-details\"><thead><tr><th scope=\"col\">Problem</th><th scope=\"col\" class=\"num\">Candidate points</th><th scope=\"col\" class=\"num\">") + esc(against) + Html(" points</th>"))
    append(Html("<th scope=\"col\" class=\"num\">Candidate vs reference budget</th><th scope=\"col\">Time used to split ties?</th></tr></thead><tbody>"))
    for (p in view.problems) {
        val cls = when {
            p.candidate > p.reference -> "DONE"
            p.candidate < p.reference -> "FAILED"
            else -> "muted"
        }
        append(Html("<tr><td><a class=\"plain\" href=\"") + problemLink(p.problem.collection, p.problem.problem) + Html("\"><code>") + esc(p.problem.collection) + Html("/"))
        append(Html("") + esc(p.problem.problem) + Html("</code></a></td><td class=\"num ") + cls + Html("\">") + ("%.2f".format(p.candidate)) + Html("</td>"))
        append(Html("<td class=\"num\">") + ("%.2f".format(p.reference)) + Html("</td>"))
        append(Html("<td class=\"num\">") + Scoreboard.seconds(p.budgetMs) + Html(" vs ") + Scoreboard.seconds(p.referenceBudgetMs) + Html("</td>"))
        val differ = p.budgetMs != p.referenceBudgetMs
        append(Html("<td class=\"") + (if (differ) "PARTIAL" else "muted") + Html("\">") + (if (p.timed) "yes" else "no, ${esc(p.untimed.orEmpty())}") + Html("</td></tr>"))
    }
    append(Html("</tbody></table></div></section>"))
}

/**
 * One scoreboard cell: candidate's share, tinted green above one half and red below, the stronger the further off, then
 * candidate's budget against the reference's (a range when its problems had several) and a quality-only mark when they differ.
 */
private fun scoreCell(cell: ScoreCell?, href: String): Html {
    val share = cell?.share ?: return (Html("<td class=\"num muted no-score\"><small>") + (if (cell == null || cell.problems == 0) "No shared results" else "No points awarded") + Html("</small></td>"))
    val strength = (kotlin.math.abs(share - HALF_SHARE) * 2 * MAX_TINT).toInt()
    val tint = "background:color-mix(in srgb, var(--${if (share >= HALF_SHARE) "ok" else "bad"}) ${strength}%, transparent)"
    val perBudget = cell.referenceBudgets.entries.joinToString(", ") { (ms, n) -> "${Scoreboard.seconds(ms)} on ${n}" }
    return buildHtml {
        append(Html("<td class=\"num\" style=\"") + tint + Html("\"><a class=\"plain\" href=\"") + href + Html("\" title=\"candidate ") + ("%.1f".format(cell.candidate)) + Html(" "))
        append(Html("against ") + ("%.1f".format(cell.reference)) + Html(" over ") + cell.problems + Html(" problems"))
        if (cell.referenceBudgets.size > 1) append(Html("; reference budgets: ") + perBudget)
        append(Html("\"><strong class=\"score-value\">") + ("%.1f".format(share * 100)) + Html("%</strong><small class=\"score-count muted\">") + cell.problems + Html(" shared problems</small>"))
        budgets(cell)?.let { append(Html("<small class=\"score-budget muted\" title=\"Candidate budget vs reference budget\">") + it + Html("</small>")) }
        append(Html("</a>"))
        if (cell.budgetsDiffer) append(Html("<small class=\"budget-flag PARTIAL\" title=\"") + (esc(qualityOnly(cell))) + Html("\">Budgets differ</small>"))
        append(Html("</td>"))
    }
}

/** A cell's budgets: candidate's against the reference's, or the range of the reference's when they vary. */
internal fun budgets(cell: ScoreCell): String? {
    val mine = cell.budgetMs ?: return null
    val theirs = cell.referenceBudgets.keys.takeIf { it.isNotEmpty() } ?: return null
    val range = if (theirs.size == 1) Scoreboard.seconds(theirs.first())
        else "${Scoreboard.seconds(theirs.min()).removeSuffix(" s")}–${Scoreboard.seconds(theirs.max())}"
    return "${Scoreboard.seconds(mine)} vs ${range}"
}

/** Why a cell whose budgets differ compares quality alone, and which way the reference's budget leans. */
private fun qualityOnly(cell: ScoreCell): String {
    val mine = cell.budgetMs ?: 0
    val other = cell.referenceBudgets.filterKeys { it != mine }
    val lean = when {
        other.keys.all { it > mine } -> "the reference had a longer budget, so its proofs and objectives had more time"
        other.keys.all { it < mine } -> "the reference had a shorter budget, so candidate's proofs and objectives had more time"
        else -> "the reference had longer budgets on some and shorter on others"
    }
    return "Time splits no equal result on the ${other.values.sum()} of ${cell.problems} problems whose reference verdict had " +
        "another budget than candidate's ${Scoreboard.seconds(mine)}, and ${lean}. The score compares quality, not speed."
}

private const val HALF_SHARE = 0.5

private const val MAX_TINT = 18

/** Families of collections that coverage folds into one expandable row: a name and which collections it holds. */
private val FAMILIES: List<Pair<String, (String) -> Boolean>> = listOf(
    "satlib" to { it.startsWith("satlib-") },
    "qfnra" to { collection -> collection == "smtlib-qf_nra" },
    "smtlib" to { it.startsWith("smtlib-") },
    "pb" to { it.startsWith("pb-") || it.startsWith("pb07-") },
    "orlib" to { it.startsWith("orlib-") },
    "miplib" to { it.startsWith("miplib") },
)

/** One collection's coverage cells: the collection (linked to its results), solver, and counts. */
private fun coverageCells(c: ReferenceCoverage, prefix: Html): Html = buildHtml {
    // The selected run's query parameters are encoded and escaped once for the whole table.
    append(Html("<td><a class=\"plain\" href=\""))
    append(prefix)
    append(esc(query("collection" to c.collection, "solver" to c.solver).removePrefix("?")))
    append(Html("#reference-search\">"))
    append(esc(c.collection))
    append(Html("</a></td><td>"))
    append(esc(c.solver))
    append(Html("</td><td class=\"num\">") + c.rows + Html("</td><td class=\"num\">") + c.decided)
    append(Html("</td><td class=\"num\">") + c.proven + Html("</td><td class=\"num\">") + c.infeasible)
    append(Html("</td><td>") + ago(c.updatedAt) + Html("</td>"))
}

/** Explicit submission lets an operator choose several filters before reloading. */
private fun referenceSelect(label: String, name: String, any: String, options: List<Pair<String, String>>, chosen: String?): Html =
    Html("<label><span>") + esc(label) + Html("</span>") + select(name, any, options, chosen, submitOnChange = false) + Html("</label>")

private fun searchParameters(filter: ReferenceFilter): Map<String, String?> = linkedMapOf(
    "q" to filter.text, "solver" to filter.solver, "collection" to filter.collection, "verdict" to filter.verdict?.name?.lowercase(),
)

private fun scoreboardParameters(view: ScoreboardView): Map<String, String?> = linkedMapOf(
    "job" to view.job?.id?.toString(), "arm" to view.arm, "by" to view.by.name.lowercase(),
    "scoring" to "incomplete".takeIf { view.scoring == Scoring.INCOMPLETE }, "group" to view.cell?.first, "against" to view.cell?.second,
).apply { for ((dimension, value) in view.filters) put(dimension.name.lowercase(), value) }

private fun hiddenParameters(params: Map<String, String?>): Html = params.entries.filter { it.value != null }.joinHtml {
    Html("<input type=\"hidden\" name=\"") + esc(it.key) + Html("\" value=\"") + esc(it.value.orEmpty()) + Html("\">")
}
