package com.eignex.lab

import java.util.concurrent.atomic.AtomicReference

/** Control paths read this sample; only the dedicated monitor performs filesystem probes. Stale samples hold work. */
internal class VolumeHealth(
    private val reason: () -> String?, private val maxAgeMs: Long = 15_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Sample(val started: Long, val problem: String?)
    private val latest = AtomicReference<Sample?>()

    @Synchronized
    fun refresh() {
        val started = clock()
        val problem = runCatching(reason).getOrElse { "the lab's volumes could not be read: ${it.javaClass.simpleName}" }
        latest.set(Sample(started, problem))
    }

    fun problem(): String? {
        val sample = latest.get() ?: return "volume health has not been checked"
        if (clock() - sample.started !in 0..maxAgeMs) return "volume health check expired; waiting for storage"
        return sample.problem
    }
}
