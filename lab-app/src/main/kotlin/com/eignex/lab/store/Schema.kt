package com.eignex.lab.store

import java.sql.Connection

/**
 * The lab database's schema. `PRAGMA user_version` counts the steps applied. A new database starts from the
 * [BASELINE] (`schema-21.sql`, every step the lab released up to version 21, squashed); [steps] after it are numbered
 * from there. [migrate] applies each in its own IMMEDIATE transaction that re-reads the version once it holds the write
 * lock, so processes that open the database together apply each step once, and a step that fails leaves no part of
 * itself. A step is never edited once released: a change to the schema is a new step at the end.
 */
internal object Schema {
    /** The version the squashed baseline stands for. A database older than it predates the squash and is refused. */
    private const val BASELINE = 21

    private val steps: List<(Connection) -> Unit> = listOf(
        // 22: a solve session may hold several active attempts, one per claim its worker named, so it refills a case
        // slot as soon as one row ends. An attempt without a claim name, from a worker that claims one at a time, stays
        // the only active one of its session.
        { db -> db.createStatement().use { s ->
            s.execute("ALTER TABLE iteration_attempts ADD COLUMN claim_id TEXT")
            s.execute("DROP INDEX active_session_iteration")
            s.execute("CREATE UNIQUE INDEX active_session_claim ON iteration_attempts (session_id, COALESCE(claim_id, '')) WHERE state IN ('claimed', 'preparing', 'executing', 'uploading')")
        } },
    )

    /** The number of steps: the `user_version` of a database [migrate] has brought up to date. */
    val version: Int get() = BASELINE + steps.size

    fun migrate(db: Connection) {
        while (true) {
            db.autoCommit = false
            try {
                val applied = db.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { r -> r.next(); r.getInt(1) } }
                check(applied <= version) { "database schema $applied is newer than this release's $version" }
                if (applied == version) { db.commit(); return }
                when {
                    applied == 0 -> {
                        check(empty(db)) { "database without a schema version holds tables: it predates schema $BASELINE; upgrade it with an earlier release" }
                        baseline(db)
                        db.createStatement().use { it.execute("PRAGMA user_version = $BASELINE") }
                    }
                    applied < BASELINE -> error("database schema $applied predates the squashed baseline $BASELINE; upgrade it with an earlier release")
                    else -> {
                        steps[applied - BASELINE](db)
                        db.createStatement().use { it.execute("PRAGMA user_version = ${applied + 1}") }
                    }
                }
                db.commit()
            } catch (e: Throwable) {
                db.rollback()
                throw e
            } finally {
                db.autoCommit = true
            }
        }
    }

    private fun empty(db: Connection): Boolean = db.createStatement().use { s ->
        s.executeQuery("SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'").use { r -> r.next(); r.getInt(1) == 0 }
    }

    /** The schema at [BASELINE]: one statement per block starting at column 0 of the resource. */
    private fun baseline(db: Connection) {
        val text = requireNotNull(Schema::class.java.getResourceAsStream("schema-$BASELINE.sql")) { "schema-$BASELINE.sql missing" }
            .bufferedReader().use { it.readText() }
        val statements = text.lines().filterNot { it.startsWith("--") }.joinToString("\n")
            .split(Regex("\n(?=CREATE |INSERT )")).map { it.trim() }.filter { it.isNotEmpty() }
        db.createStatement().use { s -> statements.forEach { s.execute(it) } }
    }

    /** Every table that keeps rows of one job, by `job_id`: what deleting the job deletes. */
    val JOB_TABLES = listOf("commands", "arms", "problems", "cases", "holds", "execution_ingestion", "execution_checks",
        "execution_queue_recipes", "execution_selections", "execution_events", "iteration_attempts", "execution_outbox",
        "execution_sessions", "trend_points")
}
