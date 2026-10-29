package com.eignex.lab

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet

/** The reference solvers' rows (`reference_rows`) and the classifier's problem features (`problem_features`). */
class ReferenceRepository internal constructor(db: Database, lock: Any) : Repository(db, lock) {
    /** Mark stale the rows whose solver version the lab no longer trusts ([References.stale]). The runner does this
     *  when it starts; opening a store writes nothing. */
    fun retireStale() = retireReferences(connection)

    private fun retireReferences(database: Connection) {
        database.autoCommit = false
        try {
            val retired = database.createStatement().use { statement ->
                statement.executeQuery("SELECT collection, problem, solver, version FROM reference_rows WHERE stale = 0").use { rows ->
                    buildList {
                        while (rows.next()) {
                            val collection = rows.getString("collection")
                            val solver = rows.getString("solver")
                            if (References.stale(solver, rows.getString("version"), collection)) {
                                add(Triple(collection, rows.getString("problem"), solver))
                            }
                        }
                    }
                }
            }
            database.prepareStatement(
                "UPDATE reference_rows SET stale = 1, updated_at = ? WHERE collection = ? AND problem = ? AND solver = ?",
            ).use { update ->
                val at = now()
                for ((collection, problem, solver) in retired) {
                    update.setLong(1, at)
                    update.setString(2, collection)
                    update.setString(3, problem)
                    update.setString(4, solver)
                    update.executeUpdate()
                }
            }
            database.commit()
        } catch (error: Throwable) {
            database.rollback()
            throw error
        } finally {
            database.autoCommit = true
        }
    }

    /**
     * Keep each of [rows] for its (collection, problem, solver) unless the stored row is stronger (see
     * [References.replaces]), so a weaker rerun never loses a proof, while a row produced another way replaces it
     * whatever its strength. Returns how many rows changed.
     */
    fun put(rows: List<Pair<ProblemKey, Reference>>, source: String): Int = synchronized(lock) {
        transaction {
            val at = now()
            var changed = 0
            val select = connection.prepareStatement("SELECT * FROM reference_rows WHERE collection = ? AND problem = ? AND solver = ?")
            val upsert = connection.prepareStatement(
                "INSERT OR REPLACE INTO reference_rows (collection, problem, solver, maximize, objective, feasible, proven, " +
                    "elapsed_ms, budget_ms, source, updated_at, version, validation, dual_bound, stale, source_identity) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            )
            select.use {
                upsert.use {
                    for ((key, captured) in rows) {
                        val row = captured.copy(stale = captured.stale ||
                            References.stale(captured.solver, captured.version, key.collection))
                        select.setString(1, key.collection)
                        select.setString(2, key.problem)
                        select.setString(3, row.solver)
                        val old = select.executeQuery().use { r -> if (r.next()) reference(r) else null }
                        if (old != null && !References.replaces(row, old)) continue
                        upsert.setString(1, key.collection)
                        upsert.setString(2, key.problem)
                        upsert.setString(3, row.solver)
                        upsert.setInt(4, if (row.maximize) 1 else 0)
                        upsert.setObject(5, row.objective)
                        upsert.setObject(6, row.feasible?.let { if (it) 1 else 0 })
                        upsert.setInt(7, if (row.proven) 1 else 0)
                        upsert.setLong(8, row.elapsedMs)
                        upsert.setLong(9, row.budgetMs)
                        upsert.setString(10, source)
                        upsert.setLong(11, at)
                        upsert.setString(12, row.version)
                        upsert.setString(13, row.validation)
                        upsert.setObject(14, row.dualBound)
                        upsert.setInt(15, if (row.stale) 1 else 0)
                        upsert.setString(16, row.sourceIdentity)
                        upsert.executeUpdate()
                        changed++
                    }
                }
            }
            changed
        }
    }

    /**
     * Mark [solver]'s current rows on [collection] stale, only those of budget [budgetMs] when given: an operator's
     * retirement, as [retireReferences] does on a policy change. The rows stay as history, the lab's comparisons and
     * coverage leave them out, a `missing` selection counts their problems as missing, and the solver's next row on
     * the problem replaces them whatever its budget ([References.replaces]). Returns how many rows it marked.
     */
    fun retire(collection: String, solver: String, budgetMs: Long? = null): Int = synchronized(lock) {
        update(
            "UPDATE reference_rows SET stale = 1, updated_at = ? WHERE collection = ? AND solver = ? AND stale = 0 AND (? IS NULL OR budget_ms = ?)",
            now(), collection, solver, budgetMs, budgetMs,
        )
    }

    /** Every reference solver's row on one problem, strongest first. */
    fun rows(collection: String, problem: String): List<Reference> =
synchronized(lock) {
                connection.prepareStatement("SELECT * FROM reference_rows WHERE collection = ? AND problem = ?").use { statement ->
                statement.setString(1, collection)
                statement.setString(2, problem)
                statement.executeQuery().use { r -> generateSequence { if (r.next()) reference(r) else null }.toList() }
            }.sortedWith { a, b -> if (References.stronger(a, b)) -1 else if (References.stronger(b, a)) 1 else 0 }
    }

    /**
     * The strongest trusted reference row of each of [keys] that has any, keyed by (collection, problem): stale rows
     * left out, and across solvers a proof another solver's solution contradicts set aside ([References.trusted]).
     * With [solver], that solver's own current row, whatever the others say: what its backfill needs to know.
     *
     * Only rows that speak for one source count ([References.applicable]): with [sources] ([References.sources]), the
     * one each problem's cases ran on. Without, as when selecting problems before any ran, the source of the newest
     * bound row, else the unbound rows: the problem's name as it now stands.
     */
    fun matching(
        keys: Collection<ProblemKey>,
        solver: String? = null,
        sources: Map<ProblemKey, String?>? = null,
    ): Map<ProblemKey, Reference> =
synchronized(lock) {
                connection.prepareStatement(
                "SELECT * FROM reference_rows WHERE collection = ? AND problem = ? AND (? IS NULL OR solver = ?)",
            ).use { statement ->
                keys.distinct().mapNotNull { key ->
                    statement.setString(1, key.collection)
                    statement.setString(2, key.problem)
                    statement.setString(3, solver)
                    statement.setString(4, solver)
                    val current = statement.executeQuery().use { r ->
                        generateSequence { if (r.next()) reference(r) to r.getLong("updated_at") else null }.toList()
                    }.filter { !it.first.stale }
                    val source = if (sources != null) sources[key]
                    else current.filter { it.first.sourceIdentity != null }.maxByOrNull { it.second }?.first?.sourceIdentity
                    val rows = References.applicable(current.map { it.first }, source)
                        .let { if (solver == null) References.trusted(it) else it }
                    rows.reduceOrNull { a, b -> if (References.stronger(b, a)) b else a }?.let { key to it }
                }.toMap()
            }
    }

    /**
     * Bind the unbound reference rows a lab run made (`source` `lab:<job>`) to the source files that run's cases
     * recorded ([References.identity]), so that rows from before sources were kept stop counting as unbound. A row
     * whose run recorded none, or one imported from elsewhere, stays unbound. Returns how many rows it bound.
     */
    fun bindLabSources(scoringCases: (Long) -> List<CaseResult>): Int {
        val jobs = synchronized(lock) {
            connection.prepareStatement(
                "SELECT DISTINCT source FROM reference_rows WHERE source_identity IS NULL AND source LIKE 'lab:%'",
            ).use { statement ->
                statement.executeQuery().use { r -> generateSequence { if (r.next()) r.getString(1) else null }.toList() }
            }.mapNotNull { it.removePrefix("lab:").toLongOrNull() }
        }
        var bound = 0
        for (job in jobs) {
            val identities = References.sources(scoringCases(job)).filterValues { it != null && it != References.MIXED_SOURCES }
            if (identities.isEmpty()) continue
            synchronized(lock) {
                transaction {
                    connection.prepareStatement(
                        "UPDATE reference_rows SET source_identity = ? WHERE collection = ? AND problem = ? AND source = ? AND source_identity IS NULL",
                    ).use { update ->
                        for ((key, identity) in identities) {
                            update.setString(1, identity)
                            update.setString(2, key.collection)
                            update.setString(3, key.problem)
                            update.setString(4, "lab:$job")
                            bound += update.executeUpdate()
                        }
                    }
                }
            }
        }
        return bound
    }

    /** Every problem two reference solvers' current rows contradict each other on, with why ([References.conflicts]). */
    fun conflicts(): List<Disagreement> = synchronized(lock) {
        connection.prepareStatement(
            "SELECT * FROM reference_rows WHERE stale = 0 AND (collection, problem) IN " +
                "(SELECT collection, problem FROM reference_rows WHERE stale = 0 GROUP BY collection, problem HAVING COUNT(*) > 1)",
        ).use { statement ->
            statement.executeQuery().use { r ->
                generateSequence { if (r.next()) ProblemKey(r.getString("collection"), r.getString("problem")) to reference(r) else null }.toList()
            }
        }.groupBy({ it.first }, { it.second }).flatMap { (key, rows) ->
            References.conflicts(rows).map { Disagreement(Problem("", key.problem, collection = key.collection), it) }
        }
    }

    /** Keep the classifier's [features] of each problem, keyed by (collection, problem); a later classification wins. */
    fun putFeatures(features: List<ProblemFeatures>) = synchronized(lock) {
        transaction {
            connection.prepareStatement(
                "INSERT OR REPLACE INTO problem_features (collection, problem, themes, structure, logic) VALUES (?, ?, ?, ?, ?)",
            ).use { upsert ->
                for (f in features) {
                    upsert.setString(1, f.collection)
                    upsert.setString(2, f.problem)
                    upsert.setString(3, Json.encodeToString(f.themes))
                    upsert.setString(4, f.structure)
                    upsert.setString(5, f.logic)
                    upsert.executeUpdate()
                }
            }
        }
    }

    /** The classifier's features of each of [keys] that has them, keyed by (collection, problem). */
    fun features(keys: Collection<ProblemKey>): Map<ProblemKey, ProblemFeatures> =
synchronized(lock) {
                connection.prepareStatement("SELECT * FROM problem_features WHERE collection = ? AND problem = ?").use { statement ->
                keys.distinct().mapNotNull { key ->
                    statement.setString(1, key.collection)
                    statement.setString(2, key.problem)
                    statement.executeQuery().use { r ->
                        if (!r.next()) return@use null
                        key to ProblemFeatures(key.collection, key.problem, Json.decodeFromString(r.getString("themes")),
                            r.getString("structure"), r.getString("logic"))
                    }
                }.toMap()
            }
    }

    /** Every current (not stale) reference row of each of [keys], by solver, keyed by (collection, problem). */
    fun current(keys: Collection<ProblemKey>): Map<ProblemKey, List<Reference>> =
synchronized(lock) {
                connection.prepareStatement("SELECT * FROM reference_rows WHERE collection = ? AND problem = ? AND stale = 0").use { statement ->
                keys.distinct().mapNotNull { key ->
                    statement.setString(1, key.collection)
                    statement.setString(2, key.problem)
                    val rows = statement.executeQuery().use { r -> generateSequence { if (r.next()) reference(r) else null }.toList() }
                    rows.takeIf { it.isNotEmpty() }?.let { key to it }
                }.toMap()
            }
    }

    /** When any reference row last changed: what tells a cached comparison against the references that it is stale. */
    fun stamp(): Long? = synchronized(lock) {
        connection.prepareStatement("SELECT MAX(updated_at) FROM reference_rows").use { statement ->
            statement.executeQuery().use { r -> if (r.next()) r.getLong(1).takeUnless { r.wasNull() } else null }
        }
    }

    /**
     * The problems whose reference rows changed after [stamp] ([referencesStamp]), each with when it last changed: what
     * tells a cached point which of its problems' comparisons moved. Read by the `updated_at` index.
     */
    fun changedSince(stamp: Long): Map<ProblemKey, Long> = synchronized(lock) {
        connection.prepareStatement(
            "SELECT collection, problem, MAX(updated_at) FROM reference_rows WHERE updated_at > ? GROUP BY collection, problem",
        ).use { statement ->
            statement.setLong(1, stamp)
            statement.executeQuery().use { r ->
                generateSequence { if (r.next()) ProblemKey(r.getString(1), r.getString(2)) to r.getLong(3) else null }.toMap()
            }
        }
    }

    /** Per collection and solver, current rows only: rows, decided, proven, infeasible, and when last updated. */
    fun coverage(): List<ReferenceCoverage> = synchronized(lock) {
        connection.prepareStatement(
            "SELECT collection, solver, COUNT(*), SUM(feasible IS NOT NULL), SUM(proven), SUM(feasible = 0), MAX(updated_at) " +
                "FROM reference_rows WHERE stale = 0 GROUP BY collection, solver ORDER BY collection, solver",
        ).use { statement ->
            statement.executeQuery().use { r ->
                generateSequence {
                    if (!r.next()) null else ReferenceCoverage(r.getString(1), r.getString(2), r.getInt(3), r.getInt(4), r.getInt(5), r.getInt(6), r.getLong(7))
                }.toList()
            }
        }
    }

    /** Up to [limit] reference rows that pass [filter], with their keys, and how many pass in all. */
    fun search(filter: ReferenceFilter, limit: Int): Pair<List<Pair<ProblemKey, Reference>>, Int> = synchronized(lock) {
        val conditions = ArrayList<String>()
        val values = ArrayList<Any>()
        filter.text?.let {
            conditions += "(problem LIKE ? ESCAPE '\\' OR collection LIKE ? ESCAPE '\\')"
            val like = "%" + likeEscape(it) + "%"
            values += like
            values += like
        }
        filter.solver?.let { conditions += "solver = ?"; values += it }
        filter.collection?.let { conditions += "collection = ?"; values += it }
        filter.verdict?.let { conditions += "(${it.sql})" }
        val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        fun bind(statement: java.sql.PreparedStatement) = values.forEachIndexed { i, v -> statement.setObject(i + 1, v) }
        val total = connection.prepareStatement("SELECT COUNT(*) FROM reference_rows $where").use { statement ->
            bind(statement)
            statement.executeQuery().use { r -> r.next(); r.getInt(1) }
        }
        val rows = connection.prepareStatement("SELECT * FROM reference_rows $where ORDER BY collection, problem, solver LIMIT ?").use { statement ->
            bind(statement)
            statement.setInt(values.size + 1, limit)
            statement.executeQuery().use { r ->
                generateSequence { if (r.next()) ProblemKey(r.getString("collection"), r.getString("problem")) to reference(r) else null }.toList()
            }
        }
        return rows to total
    }

    private fun reference(r: ResultSet) = Reference(
        solver = r.getString("solver"),
        maximize = r.getInt("maximize") == 1,
        objective = r.getDouble("objective").takeUnless { r.wasNull() },
        feasible = r.getInt("feasible").takeUnless { r.wasNull() }?.let { it == 1 },
        proven = r.getInt("proven") == 1,
        elapsedMs = r.getLong("elapsed_ms"),
        budgetMs = r.getLong("budget_ms"),
        version = r.getString("version"),
        validation = r.getString("validation"),
        dualBound = r.getDouble("dual_bound").takeUnless { r.wasNull() },
        stale = r.getInt("stale") == 1,
        sourceIdentity = r.getString("source_identity"),
    )
}
