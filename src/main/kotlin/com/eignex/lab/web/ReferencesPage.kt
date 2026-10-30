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
): Html = buildHtml {
    val visible = coverage
    append(head("reference · eignex lab", live = false, tab = Tab.REFERENCE))
    append(Html("<header><h1>Reference</h1><p class=\"muted\">") + (visible.sumOf { it.rows }) + Html(" results from "))
    append(Html("") + (visible.map { it.solver }.distinct().size) + Html(" solvers over ") + (visible.map { it.collection }.distinct().size) + Html(" collections"))
    append(Html("</p></header>"))
    scoreboard?.let { append(scoreboardSection(it)) }
    append(Html("<h2>Search</h2>"))
    append(Html("<form method=\"get\" action=\"/references\" class=\"tools filters\">"))
    append(Html("<input name=\"q\" type=\"search\" placeholder=\"problem or collection contains\" value=\"") + (esc(filter.text.orEmpty())) + Html("\">"))
    append(select("solver", "every solver", coverage.map { it.solver }.distinct().sorted().map { it to it }, filter.solver))
    append(select("collection", "every collection", visible.map { it.collection }.distinct().sorted().map { it to it }, filter.collection))
    append(select("verdict", "any verdict", ReferenceVerdict.entries.map { it.name.lowercase() to it.label }, filter.verdict?.name?.lowercase()))
    append(Html("<button>filter</button>"))
    if (!filter.isEmpty) append(Html("<a class=\"chip\" href=\"/references\">clear</a>"))
    append(Html("</form>"))
    if (!filter.isEmpty) {
        append(Html("<h2>Results <small>") + (if (total > found.size) "${found.size} of ${total}" else "${total}") + Html("</small></h2>"))
        append(Html("<div class=\"scroll\"><table><tr><th>collection</th><th>problem</th><th>verdict</th><th class=\"num\">time</th>"))
        append(Html("<th class=\"num\">budget</th></tr>"))
        for ((key, r) in found) {
            append(Html("<tr><td>") + esc(key.collection) + Html("</td><td><a class=\"plain\" href=\"") + problemLink(key.collection, key.problem) + Html("\"><code>") + esc(key.problem) + Html("</code></a></td>"))
            append(Html("<td>") + referenceVerdict(r) + Html("</td>"))
            append(Html("<td class=\"num\">") + ("%.2f".format(r.elapsedMs / 1000.0)) + Html("s</td><td class=\"num\">") + (r.budgetMs / 1000) + Html("s</td></tr>"))
        }
        append(Html("</table></div>"))
    }
    if (conflicts.isNotEmpty()) {
        append(Html("<h2 class=\"FAILED\">Solvers disagree <small>") + conflicts.size + Html(" problems where two reference solvers "))
        append(Html("contradict each other: one of them is wrong</small></h2><ul class=\"error\">"))
        for (d in conflicts) {
            append(Html("<li><a class=\"plain\" href=\"") + problemLink(d.problem.collection, d.problem.problem) + Html("\">"))
            append(Html("<code>") + esc(d.problem.collection) + Html("/") + esc(d.problem.problem) + Html("</code></a>: ") + esc(d.reason) + Html("</li>"))
        }
        append(Html("</ul>"))
    }
    val shown = visible.filter { c ->
        (filter.solver == null || c.solver == filter.solver) && (filter.collection == null || c.collection == filter.collection)
    }
    append(Html("<h2>Coverage") + (if (shown.size < visible.size) (Html(" <small>") + shown.size + Html(" of ") + visible.size + Html(" rows</small>")) else "") + Html("</h2>"))
    if (coverage.isEmpty()) append(Html("<p class=\"muted\">No reference results yet.</p>"))
    append(Html("<div class=\"scroll\"><table><tr><th>collection</th><th>solver</th><th class=\"num\">problems</th>"))
    append(Html("<th class=\"num\">decided</th><th class=\"num\">proven</th><th class=\"num\">infeasible</th><th>updated</th></tr>"))
    // A family of collections reads as one row until it is opened; each family renders as a block where its first
    // member sorts, with its members right under it.
    val families = shown.groupBy { c -> FAMILIES.firstOrNull { (_, member) -> member(c.collection) }?.first }
    val drawn = HashSet<String>()
    for (c in shown) {
        val family = FAMILIES.firstOrNull { (_, member) -> member(c.collection) }?.first
        val members = families[family].orEmpty()
        if (family == null || members.size < 2) {
            append(Html("<tr>") + coverageCells(c) + Html("</tr>"))
            continue
        }
        if (!drawn.add(family)) continue
        // The family is named in a CSS selector inside a script, so it is looked up by its row's data attribute instead.
        append(Html("<tr class=\"family\" data-family=\"") + esc(family) + Html("\" onclick=\"var f = this.dataset.family; document.querySelectorAll('tr[data-in]').forEach(function (r) { if (r.dataset.in === f) r.hidden = !r.hidden; })\">"))
        append(Html("<td>▸ <b>") + esc(family) + Html("</b> <small class=\"muted\">") + members.size + Html(" collections</small></td>"))
        append(Html("<td>") + (esc(members.map { it.solver }.distinct().joinToString())) + Html("</td><td class=\"num\">") + (members.sumOf { it.rows }) + Html("</td>"))
        append(Html("<td class=\"num\">") + (members.sumOf { it.decided }) + Html("</td><td class=\"num\">") + (members.sumOf { it.proven }) + Html("</td>"))
        append(Html("<td class=\"num\">") + (members.sumOf { it.infeasible }) + Html("</td><td>") + ago(members.maxOf { it.updatedAt }) + Html("</td></tr>"))
        for (member in members) append(Html("<tr data-in=\"") + esc(family) + Html("\" hidden>") + coverageCells(member) + Html("</tr>"))
    }
    append(Html("</table></div>"))
    append(Html(SCRIPT))
    append(Html("</body></html>"))
}

/** A candidate run against every reference solver: a row per group, a cell per solver, each cell candidate's Borda share. */
private fun scoreboardSection(view: ScoreboardView): Html = buildHtml {
    append(Html("<h2>Against the references <small>pairwise Borda by the MiniZinc Challenge rule: "))
    append(esc(if (view.scoring == Scoring.INCOMPLETE) "local-search class, proofs ignored and equal results split evenly"
        else "complete class, proofs first and equal results split by time when the run had the reference's budget on one core"))
    append(Html("</small></h2>"))
    val job = view.job
    if (job == null) {
        append(Html("<p class=\"muted\">No finished scheduled run to score yet.</p>"))
        return@buildHtml
    }
    fun link(vararg changes: Pair<String, String?>): String {
        val current = linkedMapOf<String, String?>("job" to job.id.toString(), "arm" to view.arm, "by" to view.by.name.lowercase(),
            "scoring" to "incomplete".takeIf { view.scoring == Scoring.INCOMPLETE })
        for ((d, v) in view.filters) current[d.name.lowercase()] = v
        for ((k, v) in changes) current[k] = v
        return "/references${query(*current.entries.map { it.key to it.value }.toTypedArray())}"
    }
    append(Html("<form method=\"get\" action=\"/references\" class=\"tools filters\">"))
    val runChoices = (view.runs + job).distinctBy { it.id }.map { it.id.toString() to it.name }
    append(select("job", "run", runChoices, job.id.toString()))
    if (view.arms.size > 1) append(select("arm", "arm", view.arms.map { it to it }, view.arm))
    append(select("by", "group by", Dimension.entries.map { it.name.lowercase() to "by ${it.label}" }, view.by.name.lowercase()))
    for (d in Dimension.entries) {
        val values = view.options[d].orEmpty()
        if (values.size > 1) append(select(d.name.lowercase(), "any ${d.label}", values.map { it to it }, view.filters[d]))
    }
    append(Html("<label><input type=\"checkbox\" name=\"scoring\" value=\"incomplete\""))
    if (view.scoring == Scoring.INCOMPLETE) append(Html(" checked"))
    append(Html("> local-search scoring</label>"))
    append(Html("<button>show</button>"))
    if (view.filters.isNotEmpty()) append(Html("<a class=\"chip\" href=\"") + (link(*Dimension.entries.map { it.name.lowercase() to null }.toTypedArray())) + Html("\">clear filters</a>"))
    append(Html("</form>"))
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
    append(Html("<div class=\"scroll\"><table class=\"board\"><tr><th>") + esc(view.by.label) + Html("</th><th class=\"num\">problems</th>"))
    for (solver in board.solvers) append(Html("<th class=\"num\">") + esc(solver) + Html("</th>"))
    append(Html("</tr>"))
    for (row in listOf(board.total) + board.rows) {
        append(Html("<tr") + (if (row === board.total) Html(" class=\"total\"") else "") + Html("><td>") + esc(row.group) + Html("</td><td class=\"num\">") + row.problems + Html("</td>"))
        for (solver in board.solvers) append(scoreCell(row.cells[solver], link("group" to row.group, "against" to solver)))
        append(Html("</tr>"))
    }
    append(Html("</table></div>"))
    append(Html("<p class=\"muted\">Each cell is candidate's share of the points against that solver over the problems both have a "))
    append(Html("result for; green above one half, red below. <i>best</i> takes the strongest trusted reference verdict on each problem. "))
    if (view.by == Dimension.CLASS) append(Html("The class is candidate's own profile of the model; a run that reported none takes one from the source's logic or the pipeline that solved it. "))
    append(Html("Open a cell for its problems, candidate's losses first.</p>"))
    append(Html("<p class=\"muted\">A cell reads candidate's budget against the reference's. The score measures quality against stored "))
    append(Html("reference verdicts: time splits an equal result only where the budgets are equal and candidate ran on one core, and a cell "))
    append(Html("marked <span class=\"PARTIAL\">quality only</span> has problems whose reference had another budget, which it does not "))
    append(Html("correct for. For relative speed, run a lab a/b experiment with both solvers as arms at the same budget.</p>"))
    val (group, against) = view.cell ?: return@buildHtml
    append(Html("<h3>") + esc(group) + Html(" against ") + esc(against) + Html(" <small>") + view.problems.size + Html(" problems · "))
    append(Html("<a href=\"") + link("group" to null, "against" to null) + Html("\">close</a></small></h3>"))
    append(Html("<div class=\"scroll\"><table><tr><th>problem</th><th class=\"num\">candidate</th><th class=\"num\">") + esc(against) + Html("</th>"))
    append(Html("<th class=\"num\">budgets</th><th>timed</th></tr>"))
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
    append(Html("</table></div>"))
}

/**
 * One scoreboard cell: candidate's share, tinted green above one half and red below, the stronger the further off, then
 * candidate's budget against the reference's (a range when its problems had several) and a quality-only mark when they differ.
 */
private fun scoreCell(cell: ScoreCell?, href: String): Html {
    val share = cell?.share ?: return (Html("<td class=\"num muted\">") + (if (cell == null || cell.problems == 0) "" else "–") + Html("</td>"))
    val strength = (kotlin.math.abs(share - HALF_SHARE) * 2 * MAX_TINT).toInt()
    val tint = "background:color-mix(in srgb, var(--${if (share >= HALF_SHARE) "ok" else "bad"}) ${strength}%, transparent)"
    val perBudget = cell.referenceBudgets.entries.joinToString(", ") { (ms, n) -> "${Scoreboard.seconds(ms)} on ${n}" }
    return buildHtml {
        append(Html("<td class=\"num\" style=\"") + tint + Html("\"><a class=\"plain\" href=\"") + href + Html("\" title=\"candidate ") + ("%.1f".format(cell.candidate)) + Html(" "))
        append(Html("against ") + ("%.1f".format(cell.reference)) + Html(" over ") + cell.problems + Html(" problems"))
        if (cell.referenceBudgets.size > 1) append(Html("; reference budgets: ") + perBudget)
        append(Html("\">") + ("%.2f".format(share)) + Html(" <small class=\"muted\">") + cell.problems + Html("</small>"))
        budgets(cell)?.let { append(Html("<br><small class=\"muted\">") + it + Html("</small>")) }
        append(Html("</a>"))
        if (cell.budgetsDiffer) append(Html("<br><small class=\"PARTIAL\" title=\"") + (esc(qualityOnly(cell))) + Html("\">quality only, budgets differ</small>"))
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

private const val MAX_TINT = 45

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
private fun coverageCells(c: ReferenceCoverage): Html {
    val link = "/references${query("collection" to c.collection, "solver" to c.solver)}"
    return (Html("<td><a class=\"plain\" href=\"") + link + Html("\">") + esc(c.collection) + Html("</a></td><td>") + esc(c.solver) + Html("</td>")) +
        (Html("<td class=\"num\">") + c.rows + Html("</td><td class=\"num\">") + c.decided + Html("</td><td class=\"num\">") + c.proven + Html("</td>")) +
        (Html("<td class=\"num\">") + c.infeasible + Html("</td><td>") + ago(c.updatedAt) + Html("</td>"))
}
