package com.eignex.lab.util

/**
 * A map that keeps at most [capacity] entries, the least recently used leaving first, safe to share between threads:
 * what an in-memory cache keyed by job or query keeps, so it cannot grow with the lab's history.
 */
internal class Lru<K, V>(private val capacity: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>) = size > capacity
    }

    @Synchronized operator fun get(key: K): V? = map[key]

    @Synchronized operator fun set(key: K, value: V) {
        map[key] = value
    }

    /** The value kept for [key], else [compute]d outside the lock and kept: two callers may both compute it once. */
    fun getOrPut(key: K, compute: () -> V): V = get(key) ?: compute().also { set(key, it) }

    @Synchronized fun clear() = map.clear()

    val size: Int @Synchronized get() = map.size
}
