package com.eignex.lab.worker

import com.eignex.lab.domain.ResourceCheckpoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Two bounded, credential-free receipts in the uploaded worker console, even without an attempt owner. */
internal class WorkerLifecycle(
    private val counters: () -> Map<String, String> = ResourceObservation::counters,
    private val clock: () -> Long = System::nanoTime,
    private val emit: (String) -> Unit = ::println,
) {
    @Serializable
    enum class Reason { NO_WORK, ADMISSION_WAIT_EXHAUSTED, SESSION_COMPLETE, PROVIDER_INTERRUPTION, WORKER_SHUTDOWN,
        COORDINATOR_FENCED, RUN_DEADLINE, LEASE_LOST, WORKER_FAILURE }

    @Serializable
    data class Receipt(val stage: String, val claimed: Boolean, val resources: ResourceCheckpoint?, val reason: Reason? = null,
                       val policy: String = "worker-lifecycle-v1")

    private val started = clock()
    private var observation: ResourceObservation? = null
    private var retired = false
    @Volatile var claimed = false
    @Volatile var reason = Reason.SESSION_COMPLETE

    @Synchronized fun registered(value: ResourceObservation) {
        observation = value
        write("registered", null)
    }

    @Synchronized fun retire(value: Reason = reason) {
        if (retired) return
        retired = true
        write("retired", value)
    }

    private fun write(stage: String, reason: Reason?) {
        // Failure to read diagnostics must not change retirement, recovery or process exit status.
        val resources = observation?.let { observation -> runCatching {
            ResourceCheckpoint(((clock() - started) / 1_000_000).coerceIn(0, 604_800_000), observation.budget,
                observation.enforcement, observation.cpuMax, observation.memoryMax, counters(), "worker-cumulative-lower-bound")
        }.getOrNull() }
        emit("worker lifecycle: " + Json.encodeToString(Receipt(stage, claimed, resources, reason)))
    }
}
