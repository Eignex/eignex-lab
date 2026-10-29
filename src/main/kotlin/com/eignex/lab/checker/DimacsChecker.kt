package com.eignex.lab.checker

/** DIMACS shares the existing bounded point child, input hashes and process receipts with weighted formats. */
class DimacsChecker : WeightedSourceChecker("DIMACS") {
    override val policy = "lab-dimacs-independent-complete-point-v1"
    override val checker = "eignex-lab independent original CNF point evaluator"
    override val version = DimacsSourceEvaluation.VERSION
}
