package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Capacity an operator asked for by hand ([ManualCapacityLease]), kept until it expires or is released. */
class CapacityLeases internal constructor(private val store: Store) {
    fun active(at: Long = now()): List<ManualCapacityLease> = store.executionRead { db ->
        db.sqlRows("SELECT * FROM capacity_leases WHERE expires_at > ? ORDER BY id", at) { ManualCapacityLease(it.getString("id"), it.getString("pool"), it.getInt("nodes"), it.getLong("expires_at")) }
    }
    fun request(pool: String, nodes: Int, ttlMs: Long, at: Long = now()): ManualCapacityLease = store.executionTransaction { db ->
        require(nodes > 0 && ttlMs in 1..3_600_000)
        val lease = ManualCapacityLease(java.util.UUID.randomUUID().toString(), pool, nodes, at + ttlMs)
        db.sqlUpdate("INSERT INTO capacity_leases (id, pool, nodes, expires_at) VALUES (?, ?, ?, ?)", lease.id, lease.pool, lease.nodes, lease.expiresAt)
        lease
    }
    fun release(id: String): Boolean = store.executionTransaction { it.sqlUpdate("DELETE FROM capacity_leases WHERE id = ?", id) == 1 }
}
