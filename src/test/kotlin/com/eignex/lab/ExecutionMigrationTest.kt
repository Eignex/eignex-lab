package com.eignex.lab

import com.eignex.lab.util.*
import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.execution.*
import com.eignex.lab.worker.*
import com.eignex.lab.nomad.*
import com.eignex.lab.web.*
import com.eignex.lab.cli.*

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ExecutionMigrationTest {
    private val alter = "ALTER TABLE iteration_attempts ADD COLUMN preparation_caches TEXT"
    private fun open(file: Path): Connection = DriverManager.getConnection("jdbc:sqlite:$file").also { db ->
        db.createStatement().use { it.execute("PRAGMA busy_timeout=10000") }
    }
    private fun legacy(file: Path) = open(file).use { db ->
        db.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
        ExecutionStore.migrate(db)
        db.createStatement().use {
            it.execute("ALTER TABLE iteration_attempts DROP COLUMN preparation_caches")
            it.execute("""INSERT INTO iteration_attempts
                (job_id, iteration_id, generation, session_id, allocation_id, token_hash, lease_until, state, indices, started_at, preparation_progress, preparation_updated_at, parallel_limit)
                VALUES (1, 'preparation', 1, 'session', 'allocation', 'token', 5000, 'committed', '[]', 1000, 'existing-progress', 2000, 3)""")
        }
    }

    /** Intercept only JDBC calls needed to force the real SQLite race; SQL and migration logic stay real. */
    private inline fun <reified T> intercept(delegate: T, crossinline call: (String, Array<out Any?>?, () -> Any?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args) {
                try { method.invoke(delegate, *(args ?: emptyArray())) }
                catch (failure: InvocationTargetException) { throw failure.cause!! }
            }
        } as T

    @Test fun `concurrent migrations recover when both snapshots lack the newly added column`() {
        val root = Files.createTempDirectory("execution-migration-race")
        val file = root.resolve("lab.db")
        val pool = Executors.newFixedThreadPool(2)
        try {
            legacy(file)
            val snapshot = CyclicBarrier(2)
            val attempted = AtomicInteger()
            val rejected = AtomicInteger()
            open(file).use { first -> open(file).use { second ->
                fun racing(db: Connection): Connection {
                    val synchronizedSnapshot = AtomicBoolean()
                    return intercept(db) { method, _, invoke ->
                        val result = invoke()
                        if (method != "createStatement") result else intercept(result as Statement) { operation, args, execute ->
                            if (operation == "execute" && args?.firstOrNull() == alter) {
                                attempted.incrementAndGet()
                                try { execute() } catch (failure: SQLException) { rejected.incrementAndGet(); throw failure }
                            } else {
                                val value = execute()
                                if (operation != "executeQuery" || args?.firstOrNull() != "PRAGMA table_info(iteration_attempts)" ||
                                    !synchronizedSnapshot.compareAndSet(false, true)) value
                                else intercept(value as ResultSet) { action, _, read ->
                                    val output = read()
                                    // Close the real read transaction before either connection can issue ALTER.
                                    if (action == "close") snapshot.await(10, TimeUnit.SECONDS)
                                    output
                                }
                            }
                        }
                    }
                }
                val workers = listOf(first, second).map { db -> pool.submit<Unit> { ExecutionStore.migrate(racing(db)) } }
                workers.forEach { it.get(20, TimeUnit.SECONDS) }
                assertEquals(2, attempted.get(), "both migrations must act on the same stale schema snapshot")
                assertEquals(1, rejected.get(), "one real SQLite ALTER must lose the concurrent-add race")
                first.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA table_info(iteration_attempts)").use { rows ->
                        val columns = buildList { while (rows.next()) add(rows.getString("name")) }
                        assertEquals(1, columns.count { it == "preparation_caches" })
                    }
                    statement.executeQuery("SELECT preparation_progress, preparation_updated_at, parallel_limit, preparation_caches FROM iteration_attempts").use { row ->
                        assertTrue(row.next()); assertEquals("existing-progress", row.getString(1))
                        assertEquals(2000, row.getInt(2)); assertEquals(3, row.getInt(3)); assertNull(row.getString(4))
                    }
                }
            } }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); deleteTree(root) }
    }

    @Test fun `failed column additions still propagate when rechecked schema lacks the column`() {
        val root = Files.createTempDirectory("execution-migration-failure")
        try {
            val file = root.resolve("lab.db"); legacy(file)
            val injected = SQLException("injected DDL failure")
            open(file).use { db ->
                val failing = intercept(db) { method, _, invoke ->
                    val result = invoke()
                    if (method != "createStatement") result else intercept(result as Statement) { operation, args, execute ->
                        if (operation == "execute" && args?.firstOrNull() == alter) throw injected
                        execute()
                    }
                }
                assertSame(injected, assertFailsWith<SQLException> { ExecutionStore.migrate(failing) })
                ExecutionStore.migrate(db) // Failure did not strand a transaction or damage the existing database.
            }
        } finally { deleteTree(root) }
    }
}
