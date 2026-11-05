package com.eignex.lab.reference

import com.eignex.lab.checker.*
import com.eignex.lab.domain.*


/**
 * SMT-LIB reference adapter for native cvc5, with `--tlimit` and [Z3Reference.parse].
 * Rows use solver key `cvc5`.
 */
object Cvc5Reference {
    /** The cvc5 executable, from `PATH` by default; override with `LAB_CVC5=/path/to/cvc5`. */
    private val BINARY = System.getenv("LAB_CVC5") ?: "cvc5"

    fun available(): Boolean = NativeReference.available(BINARY)

    fun run(instance: Instance, budget: Budget, binary: String = BINARY): ReferenceResult {
        val started = System.nanoTime()
        val identity = ReferenceIdentity.native(binary, budget.timeoutMillis)
        return CorpusFiles.withPlainFile(instance.model) { file ->
            val remaining = budget.timeoutMillis - (System.nanoTime() - started) / 1_000_000
            check(remaining > 0) { "cvc5 identity exhausted the reference case budget" }
            val cmd = listOf(identity.executable, "--lang=smt2", "--tlimit=$remaining", file.absolutePath)
            val (stdout, _) = NativeReference.exec(cmd, remaining)
            val result = Z3Reference.parse(stdout, (System.nanoTime() - started) / 1_000_000, cmd)
            result.copy(stats = result.stats + identity.stats +
                ("referenceVersion" to "${identity.stats.getValue("referenceVersion")}|--lang=smt2|cvc5-reference-v1"))
        }
    }
}
