package com.eignex.lab.reference


/**
 * SMT-LIB reference adapter for native cvc5, with `--tlimit` and [Z3Reference.parse].
 * Rows use solver key `cvc5`.
 */
internal object Cvc5Reference {
    /** The cvc5 executable, from `PATH` by default; override with `LAB_CVC5=/path/to/cvc5`. */
    private val BINARY = System.getenv("LAB_CVC5") ?: "cvc5"

    fun available(): Boolean = NativeReference.available(BINARY)

    fun run(instance: Instance, budget: Budget): ReferenceResult =
        CorpusFiles.withPlainFile(instance.model) { file ->
            val cmd = listOf(BINARY, "--lang=smt2", "--tlimit=${budget.timeoutMillis}", file.absolutePath)
            val (stdout, elapsedMs) = NativeReference.exec(cmd, budget.timeoutMillis)
            Z3Reference.parse(stdout, elapsedMs, cmd)
        }
}
