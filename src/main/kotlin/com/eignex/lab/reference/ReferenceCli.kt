package com.eignex.lab.reference

import java.io.File

/**
 * `eignex-lab reference key=value...`: one reference case, as a lab case command runs it. The instance comes resolved,
 * as `klause-bench select files=true` printed it: `problem=`, `format=`, `model=`, and optionally `data=` and one
 * `include=` per MiniZinc include directory. `solver=` picks one of the format's reference solvers (its default when
 * absent), `timeout=` is the budget in ms, `processors=` the solver's workers, `solver-seed=` its seed, `fixed=true`
 * keeps a MiniZinc solver to the model's search annotations, and `out=` is where the record goes. Committed-main
 * captures can use the lab's `OUT` directory when `out=` is absent.
 */
object ReferenceCli {
    private const val DEFAULT_TIMEOUT_MS = 60_000L

    fun main(args: List<String>): Int {
        val pairs = args.map { require('=' in it) { "expected key=value, got '$it'" }; it.substringBefore('=') to it.substringAfter('=') }
        val f = pairs.toMap()
        fun required(key: String) = requireNotNull(f[key]) { "missing $key=" }
        val instance = Instance(
            name = required("problem"),
            format = Format.valueOf(required("format")),
            model = File(required("model")),
            data = f["data"]?.let(::File),
            includeDirs = pairs.filter { it.first == "include" }.map { File(it.second) },
        )
        val record = ReferenceCase(
            instance = instance,
            budget = Budget(f["timeout"]?.toLong() ?: DEFAULT_TIMEOUT_MS),
            solver = f["solver"],
            processors = f["processors"]?.toInt()?.coerceAtLeast(1) ?: 1,
            seed = f["solver-seed"]?.toLong() ?: ReferenceSolve.DEFAULT_SEED,
            free = f["fixed"] != "true",
            out = File(requireNotNull(f["out"] ?: System.getenv("OUT")) { "missing out= or OUT" }),
        ).run()
        println(record)
        return 0
    }
}
