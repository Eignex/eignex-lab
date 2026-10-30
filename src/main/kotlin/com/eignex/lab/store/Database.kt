package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement
import java.util.Properties

/**
 * The lab's SQLite database: one writer connection, whose transactions are IMMEDIATE and nest, and one read-only
 * connection whose reads see one committed snapshot without waiting for the writer, as WAL allows.
 *
 * Every use of the writer happens under [lock], which callers may share (the [Store] passes itself, so its own
 * synchronized methods and these transactions exclude one another). A read on a thread that holds [lock] reads from
 * the writer instead, so it sees that thread's uncommitted writes and never waits for a lock its own thread holds.
 */
class Database(file: Path, private val busyTimeoutMs: Long = BUSY_TIMEOUT_MS, private val lock: Any = Any()) : AutoCloseable {
    /** Present while a [Store.vacuum] runs: a write in any process waits for it to end instead of failing after
     *  [busyTimeoutMs] ([waitWhileBusy]). */
    internal val vacuumMarker: Path = file.resolveSibling("${file.fileName}-vacuum")

    // IMMEDIATE takes the write lock at BEGIN. A deferred transaction that reads and then writes cannot wait for a
    // lock the other process holds, and fails with SQLITE_BUSY instead of honouring the busy timeout.
    internal val writer: Connection = DriverManager.getConnection(
        "jdbc:sqlite:$file",
        Properties().apply { setProperty("transaction_mode", "IMMEDIATE") },
    ).apply {
        org.sqlite.BusyHandler.setHandler(this, waitWhileBusy())
        // Switching a new database to WAL answers SQLITE_BUSY at once, without the busy handler, while another
        // process opening it holds a lock; so does a migration's BEGIN in a few lock orders. Both are retried.
        retryingBusy {
            createStatement().use {
                it.execute("PRAGMA journal_mode=WAL")
                it.execute("PRAGMA synchronous=FULL")
                // A checkpointed write-ahead log is cut back to this, so one large write, a vacuum's, does not keep its size.
                it.execute("PRAGMA journal_size_limit=$WAL_LIMIT_BYTES")
            }
        }
        retryingBusy { Schema.migrate(this) }
    }

    private val reader: Connection = DriverManager.getConnection("jdbc:sqlite:$file").apply {
        org.sqlite.BusyHandler.setHandler(this, waitWhileBusy())
        createStatement().use { it.execute("PRAGMA query_only=1") }
    }
    private val readLock = Any()
    private var writeDepth = 0
    private var readDepth = 0

    /** Run [block] in one IMMEDIATE transaction on the writer; a write inside another joins it. */
    fun <T> write(block: (Connection) -> T): T = synchronized(lock) {
        if (writeDepth > 0) return block(writer)
        writeDepth++
        writer.autoCommit = false
        try {
            block(writer).also { writer.commit() }
        } catch (e: Throwable) {
            writer.rollback()
            throw e
        } finally {
            writer.autoCommit = true
            writeDepth--
        }
    }

    /** Run [block] on the writer outside a transaction, each statement its own, under [lock]. */
    fun <T> autocommit(block: (Connection) -> T): T = synchronized(lock) { block(writer) }

    /**
     * Run [block] against one committed snapshot from the read-only connection, without waiting for writes; on a
     * thread that holds [lock], against the writer instead.
     */
    fun <T> read(block: (Connection) -> T): T {
        if (Thread.holdsLock(lock)) return block(writer)
        return synchronized(readLock) {
            if (readDepth > 0) return block(reader)
            readDepth++
            reader.autoCommit = false
            try {
                block(reader)
            } finally {
                runCatching { reader.rollback() }
                reader.autoCommit = true
                readDepth--
            }
        }
    }

    private fun retryingBusy(action: () -> Unit) {
        val until = System.currentTimeMillis() + busyTimeoutMs
        while (true) {
            try {
                return action()
            } catch (e: java.sql.SQLException) {
                if (e.errorCode != SQLITE_BUSY || System.currentTimeMillis() >= until) throw e
                Thread.sleep(BUSY_SLEEP_MS)
            }
        }
    }

    override fun close() {
        synchronized(readLock) { reader.close() }
        synchronized(lock) { writer.close() }
    }

    /**
     * SQLite's busy handler: retry a locked database for [busyTimeoutMs], as `busy_timeout` would, and for as long as
     * a vacuum runs, which holds the write lock for minutes, as told by [vacuumMarker]. A marker older than
     * [VACUUM_MAX_MS] is left over from a vacuum that died and is ignored.
     */
    private fun waitWhileBusy() = object : org.sqlite.BusyHandler() {
        private var since = 0L

        override fun callback(tries: Int): Int {
            val now = System.currentTimeMillis()
            if (tries == 0) since = now
            val vacuuming = runCatching { now - Files.getLastModifiedTime(vacuumMarker).toMillis() < VACUUM_MAX_MS }.getOrDefault(false)
            if (now - since >= busyTimeoutMs && !vacuuming) return 0
            Thread.sleep(minOf(tries + 1L, BUSY_SLEEP_MS))
            return 1
        }
    }

    companion object {
        /** How long a write waits for a locked database, unless a vacuum holds it ([Store.vacuum]). */
        const val BUSY_TIMEOUT_MS = 10_000L
        private const val BUSY_SLEEP_MS = 20L
        private const val SQLITE_BUSY = 5

        /** The longest a vacuum is waited for; a marker older than this was left by one that died. */
        private const val VACUUM_MAX_MS = 2 * 3_600_000L

        /** What a checkpointed write-ahead log is cut back to. */
        private const val WAL_LIMIT_BYTES = 64L shl 20
    }
}

/**
 * One open transaction or snapshot, as the code outside the store sees it: statements to run in it, and nothing that
 * could commit, roll back, close or reconfigure the connection under the store's feet.
 */
class Tx internal constructor(private val connection: Connection) {
    /** Run [sql] with [values] bound in order; how many rows it changed. */
    fun sqlUpdate(sql: String, vararg values: Any?): Int = connection.sqlUpdate(sql, *values)

    /** The rows [sql] with [values] bound in order returns, each read by [map]. */
    fun <T> sqlRows(sql: String, vararg values: Any?, map: (ResultSet) -> T): List<T> = connection.sqlRows(sql, *values, map = map)

    /** Run the INSERT [sql] with [values] bound in order; the row id it generated. */
    fun sqlInsert(sql: String, vararg values: Any?): Long = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { s ->
        values.forEachIndexed { i, value -> s.setObject(i + 1, value) }
        s.executeUpdate()
        s.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
    }
}

/** Run [sql] with [values] bound in order; how many rows it changed. */
internal fun Connection.sqlUpdate(sql: String, vararg values: Any?): Int = prepareStatement(sql).use { s ->
    values.forEachIndexed { i, value -> s.setObject(i + 1, value) }; s.executeUpdate()
}

/** The rows [sql] with [values] bound in order returns, each read by [map]. */
internal fun <T> Connection.sqlRows(sql: String, vararg values: Any?, map: (ResultSet) -> T): List<T> = prepareStatement(sql).use { s ->
    values.forEachIndexed { i, value -> s.setObject(i + 1, value) }
    s.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } }
}

/** The names of [table]'s columns. */
internal fun Statement.columns(table: String): Set<String> =
    executeQuery("PRAGMA table_info($table)").use { r -> buildSet { while (r.next()) add(r.getString("name")) } }
