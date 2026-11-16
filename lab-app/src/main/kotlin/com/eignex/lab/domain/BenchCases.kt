package com.eignex.lab.domain

import com.eignex.lab.bench.*

/** What the lab reads off a bench's planned cases, wherever the plan came from. */
object BenchCases {
    /** The identity a case is compared under across arms and revisions. */
    fun key(case: BenchCase): String = case.attributes["comparisonKey"] ?: case.id

    /** The most any case of [plan] asks for, once each case's own request is checked. */
    fun resources(plan: BenchPlan): BenchResources {
        plan.cases.forEach {
            require(it.resources.cpus in 1..1024 && it.resources.memoryMiB <= Int.MAX_VALUE && it.resources.timeoutSeconds <= 604800)
            NodeTags.validate(NodeSelector(require = it.resources.requiredTags))
        }
        require(plan.cases.isNotEmpty()) { "bench selection matched no cases" }
        return BenchResources(plan.cases.maxOf { it.resources.cpus }, plan.cases.maxOf { it.resources.memoryMiB },
            plan.cases.maxOf { it.resources.timeoutSeconds }, plan.cases.flatMap { it.resources.requiredTags }.toSet())
    }
}
