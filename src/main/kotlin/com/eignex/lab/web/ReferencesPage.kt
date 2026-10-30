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
): String = buildString {
    val visible = coverage
    append(head("reference · eignex lab", live = false, tab = Tab.REFERENCE))
    append("<header><h1>Reference</h1><p class=\"muted\">${visible.sumOf { it.rows }} results from ")
    append("${visible.map { it.solver }.distinct().size} solvers over ${visible.map { it.collection }.distinct().size} collections")
    append("</p></header>")
    scoreboard?.let { append(scoreboardSection(it)) }
    append("<h2>Search</h2>")
    append("<form method=\"get\" action=\"/references\" class=\"tools filters\">")
    append("<input name=\"q\" type=\"search\" placeholder=\"problem or collection contains\" value=\"${esc(filter.text.orEmpty())}\">")
    append(select("solver", "every solver", coverage.map { it.solver }.distinct().sorted().map { it to it }, filter.solver))
    append(select("collection", "every collection", visible.map { it.collection }.distinct().sorted().map { it to it }, filter.collection))
    append(select("verdict", "any verdict", ReferenceVerdict.entries.map { it.name.lowercase() to it.label }, filter.verdict?.name?.lowercase()))
    append("<button>filter</button>")
    if (!filter.isEmpty) append("<a class=\"chip\" href=\"/references\">clear</a>")
    append("</form>")
    if (!filter.isEmpty) {
        append("<h2>Results <small>${if (total > found.size) "${found.size} of $total" else "$total"}</small></h2>")
        append("<div class=\"scroll\"><table><tr><th>collection</th><th>problem</th><th>verdict</th><th class=\"num\">time</th>")
        append("<th class=\"num\">budget</th></tr>")
        for ((key, r) in found) {
            append("<tr><td>${esc(key.collection)}</td><td><a class=\"plain\" href=\"${problemLink(key.collection, key.problem)}\"><code>${esc(key.problem)}</code></a></td>")
            append("<td>${referenceVerdict(r)}</td>")
            append("<td class=\"num\">${"%.2f".format(r.elapsedMs / 1000.0)}s</td><td class=\"num\">${r.budgetMs / 1000}s</td></tr>")
        }
        append("</table></div>")
    }
    if (conflicts.isNotEmpty()) {
        append("<h2 class=\"FAILED\">Solvers disagree <small>${conflicts.size} problems where two reference solvers ")
        append("contradict each other: one of them is wrong</small></h2><ul class=\"error\">")
        for (d in conflicts) {
            append("<li><a class=\"plain\" href=\"${problemLink(d.problem.collection, d.problem.problem)}\">")
            append("<code>${esc(d.problem.collection)}/${esc(d.problem.problem)}</code></a>: ${esc(d.reason)}</li>")
        }
        append("</ul>")
    }
    val shown = visible.filter { c ->
        (filter.solver == null || c.solver == filter.solver) && (filter.collection == null || c.collection == filter.collection)
    }
    append("<h2>Coverage${if (shown.size < visible.size) " <small>${shown.size} of ${visible.size} rows</small>" else ""}</h2>")
    if (coverage.isEmpty()) append("<p class=\"muted\">No reference results yet.</p>")
    append("<div class=\"scroll\"><table><tr><th>collection</th><th>solver</th><th class=\"num\">problems</th>")
    append("<th class=\"num\">decided</th><th class=\"num\">proven</th><th class=\"num\">infeasible</th><th>updated</th></tr>")
    // A family of collections reads as one row until it is opened; each family renders as a block where its first
    // member sorts, with its members right under it.
    val families = shown.groupBy { c -> FAMILIES.firstOrNull { (_, member) -> member(c.collection) }?.first }
    val drawn = HashSet<String>()
    for (c in shown) {
        val family = FAMILIES.firstOrNull { (_, member) -> member(c.collection) }?.first
        val members = families[family].orEmpty()
        if (family == null || members.size < 2) {
            append("<tr>${coverageCells(c)}</tr>")
            continue
        }
        if (!drawn.add(family)) continue
        // The family is named in a CSS selector inside a script, so it is looked up by its row's data attribute instead.
        append("<tr class=\"family\" data-family=\"${esc(family)}\" onclick=\"var f = this.dataset.family; document.querySelectorAll('tr[data-in]').forEach(function (r) { if (r.dataset.in === f) r.hidden = !r.hidden; })\">")
        append("<td>▸ <b>${esc(family)}</b> <small class=\"muted\">${members.size} collections</small></td>")
        append("<td>${esc(members.map { it.solver }.distinct().joinToString())}</td><td class=\"num\">${members.sumOf { it.rows }}</td>")
        append("<td class=\"num\">${members.sumOf { it.decided }}</td><td class=\"num\">${members.sumOf { it.proven }}</td>")
        append("<td class=\"num\">${members.sumOf { it.infeasible }}</td><td>${ago(members.maxOf { it.updatedAt })}</td></tr>")
        for (member in members) append("<tr data-in=\"${esc(family)}\" hidden>${coverageCells(member)}</tr>")
    }
    append("</table></div>")
    append(SCRIPT)
    append("</body></html>")
}

/** A candidate run against every reference solver: a row per group, a cell per solver, each cell candidate's Borda share. */
private fun scoreboardSection(view: ScoreboardView): String = buildString {
    append("<h2>Against the references <small>pairwise Borda by the MiniZinc Challenge rule: ")
    append(if (view.scoring == Scoring.INCOMPLETE) "local-search class, proofs ignored and equal results split evenly"
        else "complete class, proofs first and equal results split by time when the run had the reference's budget on one core")
    append("</small></h2>")
    val job = view.job
    if (job == null) {
        append("<p class=\"muted\">No finished scheduled run to score yet.</p>")
        return@buildString
    }
    fun link(vararg changes: Pair<String, String?>): String {
        val current = linkedMapOf<String, String?>("job" to job.id.toString(), "arm" to view.arm, "by" to view.by.name.lowercase(),
            "scoring" to "incomplete".takeIf { view.scoring == Scoring.INCOMPLETE })
        for ((d, v) in view.filters) current[d.name.lowercase()] = v
        for ((k, v) in changes) current[k] = v
        return "/references${query(*current.entries.map { it.key to it.value }.toTypedArray())}"
    }
    append("<form method=\"get\" action=\"/references\" class=\"tools filters\">")
    val runChoices = (view.runs + job).distinctBy { it.id }.map { it.id.toString() to it.name }
    append(select("job", "run", runChoices, job.id.toString()))
    if (view.arms.size > 1) append(select("arm", "arm", view.arms.map { it to it }, view.arm))
    append(select("by", "group by", Dimension.entries.map { it.name.lowercase() to "by ${it.label}" }, view.by.name.lowercase()))
    for (d in Dimension.entries) {
        val values = view.options[d].orEmpty()
        if (values.size > 1) append(select(d.name.lowercase(), "any ${d.label}", values.map { it to it }, view.filters[d]))
    }
    append("<label><input type=\"checkbox\" name=\"scoring\" value=\"incomplete\"")
    if (view.scoring == Scoring.INCOMPLETE) append(" checked")
    append("> local-search scoring</label>")
    append("<button>show</button>")
    if (view.filters.isNotEmpty()) append("<a class=\"chip\" href=\"${link(*Dimension.entries.map { it.name.lowercase() to null }.toTypedArray())}\">clear filters</a>")
    append("</form>")
    val board = view.board ?: return@buildString
    if (board.total.problems == 0) {
        append("<p class=\"muted\">None of this run's problems has a reference verdict${if (view.filters.isEmpty()) "" else " under these filters"}.</p>")
        return@buildString
    }
    if (view.witnesses.isNotEmpty()) {
        val counts = WitnessCheck.entries.filter { (view.witnesses[it] ?: 0) > 0 }.joinToString(", ") { "${view.witnesses[it]} ${it.word}" }
        append("<p class=\"muted\">Solver's solutions in this run: $counts. ")
        if ((view.witnesses[WitnessCheck.UNCHECKED] ?: 0) > 0) {
            append("An unchecked solution is the solver's own report and scores as a source-checked one does. ")
        }
        append("A checked witness makes the solution valid, not its proof.</p>")
    }
    append("<div class=\"scroll\"><table class=\"board\"><tr><th>${esc(view.by.label)}</th><th class=\"num\">problems</th>")
    for (solver in board.solvers) append("<th class=\"num\">${esc(solver)}</th>")
    append("</tr>")
    for (row in listOf(board.total) + board.rows) {
        append("<tr${if (row === board.total) " class=\"total\"" else ""}><td>${esc(row.group)}</td><td class=\"num\">${row.problems}</td>")
        for (solver in board.solvers) append(scoreCell(row.cells[solver], link("group" to row.group, "against" to solver)))
        append("</tr>")
    }
    append("</table></div>")
    append("<p class=\"muted\">Each cell is candidate's share of the points against that solver over the problems both have a ")
    append("result for; green above one half, red below. <i>best</i> takes the strongest trusted reference verdict on each problem. ")
    if (view.by == Dimension.CLASS) append("The class is candidate's own profile of the model; a run that reported none takes one from the source's logic or the pipeline that solved it. ")
    append("Open a cell for its problems, candidate's losses first.</p>")
    append("<p class=\"muted\">A cell reads candidate's budget against the reference's. The score measures quality against stored ")
    append("reference verdicts: time splits an equal result only where the budgets are equal and candidate ran on one core, and a cell ")
    append("marked <span class=\"PARTIAL\">quality only</span> has problems whose reference had another budget, which it does not ")
    append("correct for. For relative speed, run a lab a/b experiment with both solvers as arms at the same budget.</p>")
    val (group, against) = view.cell ?: return@buildString
    append("<h3>${esc(group)} against ${esc(against)} <small>${view.problems.size} problems · ")
    append("<a href=\"${link("group" to null, "against" to null)}\">close</a></small></h3>")
    append("<div class=\"scroll\"><table><tr><th>problem</th><th class=\"num\">candidate</th><th class=\"num\">${esc(against)}</th>")
    append("<th class=\"num\">budgets</th><th>timed</th></tr>")
    for (p in view.problems) {
        val cls = when {
            p.candidate > p.reference -> "DONE"
            p.candidate < p.reference -> "FAILED"
            else -> "muted"
        }
        append("<tr><td><a class=\"plain\" href=\"${problemLink(p.problem.collection, p.problem.problem)}\"><code>${esc(p.problem.collection)}/")
        append("${esc(p.problem.problem)}</code></a></td><td class=\"num $cls\">${"%.2f".format(p.candidate)}</td>")
        append("<td class=\"num\">${"%.2f".format(p.reference)}</td>")
        append("<td class=\"num\">${Scoreboard.seconds(p.budgetMs)} vs ${Scoreboard.seconds(p.referenceBudgetMs)}</td>")
        val differ = p.budgetMs != p.referenceBudgetMs
        append("<td class=\"${if (differ) "PARTIAL" else "muted"}\">${if (p.timed) "yes" else "no, ${esc(p.untimed.orEmpty())}"}</td></tr>")
    }
    append("</table></div>")
}

/**
 * One scoreboard cell: candidate's share, tinted green above one half and red below, the stronger the further off, then
 * candidate's budget against the reference's (a range when its problems had several) and a quality-only mark when they differ.
 */
private fun scoreCell(cell: ScoreCell?, href: String): String {
    val share = cell?.share ?: return "<td class=\"num muted\">${if (cell == null || cell.problems == 0) "" else "–"}</td>"
    val strength = (kotlin.math.abs(share - HALF_SHARE) * 2 * MAX_TINT).toInt()
    val tint = "background:color-mix(in srgb, var(--${if (share >= HALF_SHARE) "ok" else "bad"}) $strength%, transparent)"
    val perBudget = cell.referenceBudgets.entries.joinToString(", ") { (ms, n) -> "${Scoreboard.seconds(ms)} on $n" }
    return buildString {
        append("<td class=\"num\" style=\"$tint\"><a class=\"plain\" href=\"$href\" title=\"candidate ${"%.1f".format(cell.candidate)} ")
        append("against ${"%.1f".format(cell.reference)} over ${cell.problems} problems")
        if (cell.referenceBudgets.size > 1) append("; reference budgets: $perBudget")
        append("\">${"%.2f".format(share)} <small class=\"muted\">${cell.problems}</small>")
        budgets(cell)?.let { append("<br><small class=\"muted\">$it</small>") }
        append("</a>")
        if (cell.budgetsDiffer) append("<br><small class=\"PARTIAL\" title=\"${esc(qualityOnly(cell))}\">quality only, budgets differ</small>")
        append("</td>")
    }
}

/** A cell's budgets: candidate's against the reference's, or the range of the reference's when they vary. */
internal fun budgets(cell: ScoreCell): String? {
    val mine = cell.budgetMs ?: return null
    val theirs = cell.referenceBudgets.keys.takeIf { it.isNotEmpty() } ?: return null
    val range = if (theirs.size == 1) Scoreboard.seconds(theirs.first())
        else "${Scoreboard.seconds(theirs.min()).removeSuffix(" s")}–${Scoreboard.seconds(theirs.max())}"
    return "${Scoreboard.seconds(mine)} vs $range"
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
        "another budget than candidate's ${Scoreboard.seconds(mine)}, and $lean. The score compares quality, not speed."
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
private fun coverageCells(c: ReferenceCoverage): String {
    val link = "/references${query("collection" to c.collection, "solver" to c.solver)}"
    return "<td><a class=\"plain\" href=\"$link\">${esc(c.collection)}</a></td><td>${esc(c.solver)}</td>" +
        "<td class=\"num\">${c.rows}</td><td class=\"num\">${c.decided}</td><td class=\"num\">${c.proven}</td>" +
        "<td class=\"num\">${c.infeasible}</td><td>${ago(c.updatedAt)}</td>"
}
