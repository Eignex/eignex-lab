package com.eignex.lab.bench

/** Repository-owned planning and measurement, hosted behind the SDK's process contract. */
public interface BenchSuite {
    /** Describe this suite without building or running workloads. */
    public fun describe(): BenchDescriptor

    /** Expand selection parameters into concrete cases without measuring them. */
    public fun plan(request: BenchRequest): List<BenchCase>

    /** Execute one case and retain its captures under [context]'s output directory. */
    public fun run(case: BenchCase, context: BenchContext): BenchResult
}
