package com.eignex.lab.cli

import com.eignex.lab.analysis.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*

import kotlinx.serialization.json.Json
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

/**
 * The coordinator's check of each finished case, the reconciler's `checkCase`: one check at a time on a thread of its
 * own, at most [MAX_PENDING] waiting, so a pass never blocks on a checker. A reference arm's case records its reference
 * instead of being checked. [check] answers null until a case's check is done, and [checked] wakes the reconciler then.
 */
internal class CoordinatorCaseChecks(
    private val store: Store,
    private val checks: CaseChecks,
    /** Appends a line to a job's setup log. */
    private val log: (Long, String) -> Unit,
    private val checked: () -> Unit,
) {
    private val pool = Executors.newSingleThreadExecutor { task -> Thread(task, "nomad-source-checks").apply { isDaemon = true } }
    private val pending = ConcurrentHashMap<Pair<Long, Int>, Future<String>>()

    fun check(job: Long, index: Int): String? {
        val (problem, arm) = store.plans.caseOf(job, index) ?: error("case missing")
        val reference = arm.values["backend"] == REFERENCE_BACKEND
        if (!reference && !ValidationRequest.requested(store.job(job)?.experiment, arm.values)) return "not-required"
        val key = job to index
        if (pending.size >= MAX_PENDING && !pending.containsKey(key)) return null
        val future = pending.computeIfAbsent(key) {
            val task = object : FutureTask<String>(Callable {
                if (reference) {
                    record(job, index, problem)
                    "not-required"
                } else {
                    checks.check(job, index, problem)?.json() ?: "unchecked: no applicable source checker"
                }
            }) { override fun done() = checked() }
            pool.execute(task)
            task
        }
        if (!future.isDone) return null
        return try {
            future.get()
        } catch (failure: ExecutionException) {
            if (failure.cause is SharedCheckInputUnavailable) return null
            val detail = "unchecked: source checker failed (${failure.cause?.javaClass?.simpleName})"
            log(job, "case $index: $detail")
            detail
        } finally {
            pending.remove(key, future)
        }
    }

    /** A reference arm's case: its result becomes the problem's reference row. */
    private fun record(job: Long, index: Int, problem: Problem) {
        if (problem.collection.isEmpty()) return
        val text = store.plans.caseRecordText(job, index) ?: return
        val reference = References.of(Json.parseToJsonElement(text)) ?: return
        store.references.put(listOf(problem.referenceKey to reference), "lab:$job")
    }

    private companion object {
        const val MAX_PENDING = 64
    }
}
