package com.eignex.lab

import java.sql.Connection
import java.sql.ResultSet

/**
 * What a part of the [Store] queries through: the store's [Database], and its [lock], which every use of the writer
 * holds (a method that writes outside [transaction] takes it with `synchronized(lock)`). Reads go through [reading],
 * which reads one committed snapshot without waiting for writes.
 */
abstract class Repository internal constructor(private val db: Database, protected val lock: Any) {
    private val readConnection = ThreadLocal<Connection>()

    /** The connection this thread's queries use: the snapshot reader inside [reading], else the writer. */
    protected val connection: Connection get() = readConnection.get() ?: db.writer

    protected fun <T> reading(block: () -> T): T = db.read { conn ->
        if (conn === db.writer) block() else {
            readConnection.set(conn)
            try { block() } finally { readConnection.remove() }
        }
    }

    /** One IMMEDIATE transaction; one begun inside another joins it ([Database.write]). */
    protected fun <T> transaction(block: () -> T): T = db.write { block() }

    protected fun update(sql: String, vararg values: Any?): Int = connection.sqlUpdate(sql, *values)

    protected fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }
    protected fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }
}

/** [text] for a LIKE pattern with `\\` as its escape: its own `%`, `_` and `\\` match only themselves. */
internal fun likeEscape(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
