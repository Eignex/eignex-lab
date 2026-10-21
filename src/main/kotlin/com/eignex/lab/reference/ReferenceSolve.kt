package com.eignex.lab.reference

/** Which reference solver takes an instance, and running it. */
internal object ReferenceSolve {
    /** A reference solve: the solver that ran, its result, and the model's objective sense. */
    data class Run(
        val solver: String,
        val result: ReferenceResult,
        val optimize: Boolean,
        val maximize: Boolean,
    )

    /** The MiniZinc solver a reference arm without `solver=` uses. */
    const val MINIZINC_DEFAULT = "cp-sat"

    /** The seed a case without `solver-seed=` runs with, as in klause-bench. */
    const val DEFAULT_SEED = 3L

    /** The reference solvers each format can be solved by, the default first; MiniZinc takes any
     *  `minizinc --solver`. */
    val SOLVERS: Map<Format, List<String>> = mapOf(
        Format.DIMACS to listOf("clasp", "kissat"),
        Format.OPB to listOf("clasp"),
        Format.WCNF to listOf("clasp"),
        Format.SMTLIB to listOf("z3", "cvc5"),
        Format.MPS to listOf("scip", "highs"),
        Format.XCSP3 to listOf("cp-sat"),
    )

    /** The solver [format] is referenced by: [solver] when given and allowed, else the format's default. */
    fun solverIdFor(format: Format, solver: String? = null): String {
        val known = SOLVERS[format]
        val id = solver ?: known?.first() ?: MINIZINC_DEFAULT
        if (known != null) {
            require(id in known) { "reference solver '$id' does not solve $format; it is solved by ${known.joinToString()}" }
        }
        return id
    }

    /**
     * Solve [instance] with [solver], or with its format's default reference when null. A second solver for a format,
     * such as kissat beside clasp or HiGHS beside SCIP, gives each problem a second verdict: the lab keeps the stronger,
     * and two that contradict each other show which reference not to trust. [processors] pins the solver's workers,
     * one unless asked: cases run side by side, and a solver that starts one per core makes its times measure the load.
     */
    fun run(
        instance: Instance,
        budget: Budget,
        solver: String? = null,
        processors: Int = 1,
        seed: Long = DEFAULT_SEED,
        free: Boolean = true,
    ): Run {
        val id = solverIdFor(instance.format, solver)
        return when (instance.format) {
            Format.DIMACS -> {
                val r = if (id == "kissat") KissatReference.run(instance, budget) else ClaspReference.run(instance, budget)
                Run(id, r, optimize = r.exactObjective != null || r.objective != null, maximize = false)
            }

            // clasp minimises OPB (its only PB sense); the result carries the sense.
            Format.OPB, Format.WCNF -> {
                val r = ClaspReference.run(instance, budget)
                Run(id, r, optimize = true, maximize = r.stats["maximize"].toBoolean())
            }

            Format.XCSP3 -> {
                val r = Xcsp3CpSatReference.run(instance, budget, processors, seed)
                Run(id, r, optimize = r.exactObjective != null || r.objective != null,
                    maximize = r.stats["maximize"].toBoolean())
            }

            // SMT-LIB benchmarks are decision instances: no objective to orient.
            Format.SMTLIB -> Run(
                id,
                if (id == "cvc5") Cvc5Reference.run(instance, budget) else Z3Reference.run(instance, budget),
                optimize = false,
                maximize = false,
            )

            // SCIP and HiGHS report the bound in the model's OBJSENSE orientation, carried in stats.
            Format.MPS -> {
                val r = if (id == "highs") HighsReference.run(instance, budget) else ScipReference.run(instance, budget)
                Run(id, r, optimize = true, maximize = r.stats["maximize"].toBoolean())
            }

            Format.MINIZINC -> {
                val (optimize, maximize) = MiniZincReference.solveKind(instance)
                Run(id, MiniZincReference.run(instance, id, budget, processors, free), optimize, maximize)
            }
        }
    }
}
