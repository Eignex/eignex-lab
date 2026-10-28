package com.eignex.lab.checker

import java.io.File

/**
 * MiniZinc output can omit internal assignments. Completing those assignments with Gecode would run a solver,
 * rather than validate the supplied witness. Until an independent assignment checker is available, leave the claim
 * unsupported. MiniZinc and its solvers remain available for compilation and explicit reference runs only.
 */
class MiniZincChecker : FormatChecker {
    override val format = "MINIZINC"
    override val policy = Policies.MINIZINC
    override val checker = "none"

    override fun check(case: CheckCase, work: File) = Verdict(
        LabCheck.UNSUPPORTED,
        "no independent MiniZinc assignment checker is available; solver-based witness completion is disabled",
    )
}
