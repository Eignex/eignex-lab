package com.eignex.lab.domain

import com.eignex.lab.bench.*

import kotlinx.serialization.Serializable

/** A repository-owned suite. The committed manifest owns its build and launch recipe. */
@Serializable
data class BenchSpec(
    val manifest: String,
    val request: BenchRequest = BenchRequest(),
    val runTimeoutSec: Long? = null,
    val sourceValidation: Boolean = false,
    /** Exact workloads retained by a confirmation; runtime parameters still come from each approved revision. */
    val frozenCases: List<BenchCase> = emptyList(),
    val referenceFilter: ReferenceFilterMode = ReferenceFilterMode.ANY,
    val referenceFilters: Map<String, ReferenceFilterMode> = emptyMap(),
    val replayJobs: Map<String, Long> = emptyMap(),
    /** External Linux timing of the adapter's `run` invocation and its waited child processes. */
    val adapterResources: Boolean = false,
) {
    init {
        safeRelative(manifest)
        require(runTimeoutSec == null || runTimeoutSec in 1..7200) { "bench runTimeoutSec must be between 1 and 7200" }
    }
}
