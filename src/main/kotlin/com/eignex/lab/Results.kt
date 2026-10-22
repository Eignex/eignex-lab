package com.eignex.lab

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Experiment results out of the lab as CSV. */
object Results {
    /**
     * Every case with a record as one CSV row: the case, its problem and arm, and the record's verdict and times.
     * `solveTimeMs` is the whole solve, which is how long a proof took when there is no solution to time; `timeMs` is
     * the time the comparisons score: to the best solution, else the solve for a proof, else the budget.
     */
    fun casesCsv(cases: List<CaseResult>): String = buildString {
        appendLine(
            "index,status,arm,seed,repeat,suite,problem,collection,family,format,kind,maximize,feasible,objective,proven," +
                "timeToBestMs,timeToFirstFeasibleMs,solveTimeMs,timeMs,budgetMs,error,unsupported,loadError,sourceValidation,floatApproximation,buildFingerprint,validationPolicy,host",
        )
        for (case in cases) {
            val record = case.record as? JsonObject
            val outcome = Outcome.of(record)
            fun field(name: String) = (record?.get(name) as? JsonPrimitive)?.content?.takeUnless { it == "null" }.orEmpty()
            fun stat(name: String) = ((record?.get("stats") as? JsonObject)?.get(name) as? JsonPrimitive)?.content.orEmpty()
            val row = listOf(
                case.index.toString(), case.status.name, case.arm, case.seed?.toString().orEmpty(), case.repeat.toString(),
                case.problem.suite,
                case.problem.problem, case.problem.collection, case.problem.family, case.problem.format, field("kind"),
                field("maximize"), outcome?.feasible?.toString().orEmpty(), outcome?.objective?.toString().orEmpty(),
                outcome?.proven?.toString().orEmpty(), outcome?.timeToBestMs?.toString().orEmpty(),
                if (outcome?.feasible == true) field("timeToFirstFeasibleMs") else "",
                outcome?.solveTimeMs?.toString().orEmpty(), outcome?.timeMs?.toString().orEmpty(), field("budgetMs"), (field("command") == "ERROR").toString(),
                stat("unsupported"), stat("loadError"), outcome?.sourceValidation.orEmpty(), stat("floatApproximation"),
                field("buildFingerprint"), field("validationPolicy"), case.host.orEmpty(),
            )
            appendLine(row.joinToString(",") { cell(it) })
        }
    }

    /**
     * One arm's results as a plain CSV, for analysis outside the lab: keyed by (collection, problem), one row per
     * problem, from the cases of [seed] (null: the cases run without one). The problem feature columns stay blank.
     */
    fun benchCsv(cases: List<CaseResult>, arm: String, seed: Long?): String = buildString {
        appendLine("suite,problem,maximize,objective,feasible,proven,elapsedMs,budgetMs,format,structure,numGlobal,numLinear,boolHeavy,logic")
        val rows = cases.filter { it.arm == arm && it.seed == seed && it.repeat == 0 }
            .mapNotNull { case -> Outcome.of(case.record)?.let { case.problem to it } }
            .sortedWith(compareBy({ it.first.collection }, { it.first.problem }))
        for ((problem, outcome) in rows) {
            val row = listOf(
                cell(problem.collection), cell(problem.problem), outcome.maximize.toString(),
                outcome.objective?.toString().orEmpty(), outcome.feasible?.toString().orEmpty(), outcome.proven.toString(),
                outcome.timeMs.toString(), outcome.budgetMs.toString(), "", "", "", "", "", "",
            )
            appendLine(row.joinToString(","))
        }
    }

    private fun cell(text: String) =
        if (text.any { it == ',' || it == '"' || it == '\n' }) "\"" + text.replace("\"", "\"\"") + "\"" else text

}
