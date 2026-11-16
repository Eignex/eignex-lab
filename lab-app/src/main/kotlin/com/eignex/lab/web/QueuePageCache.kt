package com.eignex.lab.web

import com.eignex.lab.util.periodic

/** Bounded display-only snapshots. No database reads, estimates or probes run on a request thread. */
internal class QueuePageCache(
    private val read: (Key) -> Html,
    private val loading: (Key) -> Html,
    private val everyMs: Long = 5_000,
    private val maxAgeMs: Long = 30_000,
    private val idleMs: Long = 30_000,
    private val limit: Int = 16,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : AutoCloseable {
    internal data class Key(val before: Long?, val name: String?)
    private data class Sample(val at: Long, val page: Html)
    private class Entry(var requested: Long, var sample: Sample? = null, var attempted: Long? = null)

    private val front = Key(null, null)
    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true).apply { put(front, Entry(clock())) }
    private var worker: Thread? = null

    init { require(limit >= 2) }

    fun get(before: Long?, name: String?): Html {
        val key = Key(before, name)
        val sample = synchronized(entries) {
            val entry = entries.getOrPut(key) { Entry(clock()) }
            entry.requested = clock()
            while (entries.size > limit) entries.remove(entries.keys.first { it != front })
            entry.sample
        }
        val page = sample?.page ?: loading(key)
        val age = sample?.let { (clock() - it.at).coerceAtLeast(0) }
        val message = when {
            age == null -> "Queue snapshot is loading; this page refreshes automatically."
            age > maxAgeMs -> "Queue snapshot is stale (${age / 1000}s old); waiting for the next background refresh."
            else -> "Queue snapshot refreshes in the background."
        }
        return Html(page.markup.replace("<!--queue-snapshot-->", esc(message).markup))
    }

    fun start() {
        check(worker == null)
        worker = periodic("queue-page", 100) {
            val at = clock()
            val selected = synchronized(entries) {
                entries.entries.removeIf { it.key != front && at - it.value.requested > idleMs }
                val due = entries.entries.filter {
                    at - it.value.requested <= idleMs && it.value.attempted?.let { last -> at - last >= everyMs } != false
                }
                // The front page stays warm even when filtered history pages are being requested.
                (due.firstOrNull { it.key == front } ?: due.minByOrNull { it.value.attempted ?: Long.MIN_VALUE })
                    ?.let { (key, entry) -> entry.attempted = at; key to entry }
            }
            if (selected != null) {
                val (key, entry) = selected
                val page = read(key)
                synchronized(entries) {
                    if (entries[key] === entry) entry.sample = Sample(at, page)
                }
            }
        }
    }

    override fun close() { worker?.interrupt() }
}
