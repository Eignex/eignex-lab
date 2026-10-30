package com.eignex.lab.execution

import com.eignex.lab.util.*

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Forgets ended jobs [maxAgeMs] after they ended (`LAB_JOB_RETENTION_DAYS`): [forget] deletes a job's record and
 * directory, as `lab rm` does, and refuses one whose Nomad stop is pending. A job a download holds ([held]) is kept.
 */
class JobRetention(
    /** Every ended job: its id and when it ended. */
    private val ended: () -> List<Pair<Long, Long>>,
    private val maxAgeMs: Long,
    private val held: () -> Set<Long> = { emptySet() },
    private val forget: (Long) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val report: (String) -> Unit = ::println,
) {
    /** Forget what is due now; returns how many jobs went. */
    fun run(): Int {
        val now = clock()
        val due = ended().filter { (_, at) -> now - at >= maxAgeMs }.map { it.first }
        if (due.isEmpty()) return 0
        val kept = held()
        val forgotten = due.filter { it !in kept }.count { id -> runCatching { forget(id) }.getOrDefault(false) }
        if (forgotten > 0) report("job retention: forgot $forgotten job${if (forgotten == 1) "" else "s"} that ended over ${maxAgeMs / MS_PER_DAY} days ago")
        return forgotten
    }

    /** Run [run] now and then every [INTERVAL_MIN] minutes, on a daemon thread. */
    fun start() {
        Executors.newSingleThreadScheduledExecutor { Thread(it, "job-retention").apply { isDaemon = true } }
            .scheduleWithFixedDelay({ runCatching { run() }.onFailure { println("job retention: ${it.message}") } }, 2, INTERVAL_MIN, TimeUnit.MINUTES)
    }

    internal companion object {
        const val INTERVAL_MIN = 60L
    }
}
