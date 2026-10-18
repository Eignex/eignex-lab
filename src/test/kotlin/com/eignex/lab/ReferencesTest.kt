package com.eignex.lab

import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ReferencesTest {
    private fun ref(feasible: Boolean?, objective: Double? = null, proven: Boolean = false) =
        Reference("cp-sat", maximize = false, objective = objective, feasible = feasible, proven = proven, elapsedMs = 10, budgetMs = 1000)

    @Test
    fun `a re-import keeps the stronger result already stored`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val key = "c" to "p"
        store.putReferences(listOf(key to ref(true, 5.0, proven = true)), "a")

        val changed = store.putReferences(listOf(key to ref(true, 9.0)), "b")

        assertEquals(0 to 5.0, changed to store.references(listOf(key)).getValue(key).objective)
    }

    @Test
    fun `an arm proving infeasible what the reference solved is a disagreement`() {
        val case = CaseResult(0, Status.DONE, Problem("s", "p", collection = "c"), "a", null,
            Json.parseToJsonElement("""{"kind":"satisfy","feasible":false,"proven":true,"budgetMs":1000}"""))

        val comparison = References.compare(listOf("a"), listOf(case), mapOf(("c" to "p") to ref(true)))

        assertEquals(listOf("a proves infeasible, cp-sat solved it"), comparison.disagreements.map { it.reason })
    }

    @Test
    fun `a worse incumbent is listed with its gap, the largest first`() {
        fun case(problem: String, objective: Int) = CaseResult(0, Status.DONE, Problem("s", problem, collection = "c"), "a", null,
            Json.parseToJsonElement("""{"kind":"optimize","maximize":false,"feasible":true,"objective":$objective,"timeToBestMs":5,"budgetMs":1000}"""))
        val proven = Reference("cp-sat", false, 10.0, true, true, 5, 1000)

        val comparison = References.compare(
            listOf("a"),
            listOf(case("close", 11), case("far", 20), case("equal", 10)),
            mapOf(("c" to "close") to proven, ("c" to "far") to proven, ("c" to "equal") to proven),
        )

        assertEquals(listOf("far" to 1.0, "close" to 0.1), comparison.shortfalls.map { it.problem.problem to it.gap })
        assertEquals(2, comparison.summaries.single().worse)
    }

    @Test
    fun `two reference solvers that contradict each other are a conflict, and agreeing ones are not`() {
        val unsat = Reference("clasp", false, null, false, true, 10, 60_000)
        val sat = Reference("kissat", false, null, true, true, 10, 60_000)
        val optimum = Reference("scip", false, 10.0, true, true, 10, 60_000)

        assertEquals(listOf("clasp proves infeasible, kissat found a solution"), References.conflicts(listOf(unsat, sat)))
        assertEquals(listOf("highs proves optimum 9, scip proves 10"), References.conflicts(listOf(optimum, optimum.copy(solver = "highs", objective = 9.0))))
        assertEquals(listOf("highs's solution 8 beats scip's proven optimum 10"),
            References.conflicts(listOf(optimum, optimum.copy(solver = "highs", objective = 8.0, proven = false))))
        assertEquals(emptyList(), References.conflicts(listOf(optimum, optimum.copy(solver = "highs"))))
    }

    @Test
    fun `optima within the solvers' tolerance agree, and only a real difference conflicts`() {
        val scip = Reference("scip", false, 1480.0, true, true, 10, 60_000)

        assertEquals(emptyList(), References.conflicts(listOf(scip, scip.copy(solver = "highs", objective = 1479.99999999))))
        assertEquals(emptyList(), References.conflicts(listOf(scip.copy(objective = -2451377.0), scip.copy(solver = "highs", objective = -2451279.0))))
        assertEquals(listOf("highs proves optimum 1470, scip proves 1480"),
            References.conflicts(listOf(scip, scip.copy(solver = "highs", objective = 1470.0))))
    }

    @Test
    fun `a filter keeps the rows of its solver and verdict, and counts them all`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        store.putReferences(
            listOf(
                ("c" to "a") to ref(true, 1.0, proven = true),
                ("c" to "b") to ref(false, proven = true),
                ("c" to "c") to ref(null),
                ("d" to "a") to ref(true, 2.0, proven = true).copy(solver = "z3"),
            ),
            "test",
        )

        val (rows, total) = store.searchReferences(ReferenceFilter(solver = "cp-sat", verdict = ReferenceVerdict.OPTIMUM), limit = 10)

        assertEquals(listOf("c" to "a") to 1, rows.map { it.first } to total)
    }

    @Test
    fun `a problem's runs come from every experiment that ran it`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val problems = listOf(Problem("s", "p", collection = "c"), Problem("s", "q", collection = "c"))
        val id = store.create("e", "main", emptyList(), experiment = ExperimentSpec("e", listOf(mapOf("suite" to "s"))))
        store.plan(id, listOf(PlannedArm(Arm("base", emptyMap()), "sha")), problems, Experiments.cases(2, 1, emptyList()), listOf("true" to 1L, "true" to 1L))

        val runs = store.problemRuns("c", "q")

        assertEquals(listOf(id to 1), runs.map { it.job to it.case })
    }

    @Test
    fun `a reference arm's record becomes a row timed by its solve time, and an error none`() {
        val proof = """{"solver":"clasp","kind":"satisfy","maximize":false,"feasible":true,"proven":true,"budgetMs":10000,
            "command":"docker run","stats":{"solveTime":"0.25"}}"""
        val error = """{"solver":"clasp","kind":"satisfy","budgetMs":10000,"command":"ERROR"}"""

        val rows = listOf(proof, error).map { References.of(Json.parseToJsonElement(it)) }

        assertEquals(listOf(Reference("clasp", false, null, true, true, 250, 10000), null), rows)
    }

    @Test
    fun `polynomial real references persist with their binary identity and enter ordinary comparisons`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val key = "klause-bench/smoke-corpus/qfnra-v1/core/algebraic" to "algebraic/irrational-tower"
        val record = Json.parseToJsonElement("""{"solver":"z3","kind":"satisfy","feasible":true,"proven":true,
            "budgetMs":10000,"command":"z3 -model input.smt2","stats":{"solveTime":"0.006",
            "referenceVersion":"Z3 pinned|sha256=abc|smt-reference-v2","referenceStatus":"sat"}}""")
        val row = requireNotNull(References.of(record))

        store.putReferences(listOf(key to row), "lab:1")
        val stored = store.references(listOf(key), "z3")

        assertEquals(row, stored[key])
        assertEquals("Z3 pinned|sha256=abc|smt-reference-v2", store.referenceRows(key.first, key.second).single().version)
    }

    @Test
    fun `reference parser errors do not become trusted reference rows`() {
        val record = Json.parseToJsonElement("""{"solver":"z3","kind":"satisfy","budgetMs":10000,
            "command":"z3 input.smt2","stats":{"referenceError":"unsupported assertion"}}""")

        assertEquals(null, References.of(record))
    }

    private fun mps(solver: String, feasible: Boolean?, objective: Double?, proven: Boolean, version: String = "$solver|mps-validate-1", dualBound: Double? = null) =
        Reference(solver, maximize = false, objective = objective, feasible = feasible, proven = proven, elapsedMs = 10,
            budgetMs = 60_000, version = version, dualBound = dualBound, stale = version.isEmpty())

    @Test
    fun `a checked rerun replaces an invalid stored proof, and an unchecked one never replaces a checked row`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val key = "miplib2017" to "neos4"
        store.putReferences(listOf(key to mps("highs", true, -48454383704.3946, proven = true, version = "")), "old")
        val corrected = mps("highs", true, -48603440750.5895, proven = false)

        val replaced = store.putReferences(listOf(key to corrected), "new")
        val unchecked = store.putReferences(listOf(key to mps("highs", true, -48454383704.3946, proven = true, version = "")), "old bench")

        assertEquals(listOf(1, 0), listOf(replaced, unchecked))
        assertEquals(corrected, store.references(listOf(key), "highs")[key])
    }

    @Test
    fun `a proof another solver's checked solution contradicts is set aside, the rows kept as evidence`() {
        val infeasible = mps("highs", false, null, proven = true)
        val witness = mps("scip", true, 619244367.66, proven = false)
        val excluded = mps("highs", true, 317080.0, proven = true, dualBound = 317070.0)
        val better = mps("scip", true, 317056.21, proven = false)
        val agreed = mps("scip", true, 10.0, proven = true)

        val trusted = References.trusted(listOf(infeasible, witness)) + References.trusted(listOf(excluded, better)) +
            References.trusted(listOf(agreed, mps("highs", true, 10.0, proven = false)))

        assertEquals(listOf(null to false, true to false, true to false, true to false, true to true, true to false),
            trusted.map { it.feasible to it.proven })
        assertEquals(listOf("highs proves infeasible, scip found a solution"), References.conflicts(listOf(infeasible, witness)))
    }

    @Test
    fun `the lab's comparisons take neither stale rows nor disputed proofs`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val key = "miplib2017" to "neos-1603965"
        store.putReferences(listOf(key to mps("scip", true, 637213553.1165, proven = false)), "scip")
        store.putReferences(listOf(key to mps("highs", false, null, proven = true)), "highs")
        val stale = "miplib2017" to "marne"
        store.putReferences(listOf(stale to mps("scip", true, 317056.2, proven = true, version = "")), "old")

        val trusted = store.references(listOf(key, stale))

        assertEquals(listOf("scip" to true), trusted.values.map { it.solver to it.feasible })
        assertEquals(2, store.referenceRows(key.first, key.second).size)
    }

    @Test
    fun `opening a database from before checked MPS references marks their rows stale`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use {
                it.execute("""CREATE TABLE reference_rows (
                    collection TEXT NOT NULL, problem TEXT NOT NULL, solver TEXT NOT NULL, maximize INTEGER NOT NULL,
                    objective REAL, feasible INTEGER, proven INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL,
                    budget_ms INTEGER NOT NULL, source TEXT NOT NULL, updated_at INTEGER NOT NULL,
                    PRIMARY KEY (collection, problem, solver))""")
                it.execute("INSERT INTO reference_rows VALUES ('miplib2017', 'neos4', 'highs', 0, -4.8e10, 1, 1, 10, 60000, 'old', 0)")
                it.execute("INSERT INTO reference_rows VALUES ('hakank', 'q', 'cp-sat', 0, 8, 1, 1, 10, 60000, 'old', 0)")
            }
        }

        val store = Store(file)

        assertEquals(listOf(true, false), listOf("miplib2017" to "neos4", "hakank" to "q").map { store.referenceRows(it.first, it.second).single().stale })
        assertEquals(setOf("hakank" to "q"), store.references(listOf("miplib2017" to "neos4", "hakank" to "q")).keys)
    }

    @Test
    fun `a checked MPS record carries its version, validation and dual bound, an unchecked one arrives stale`() {
        fun record(stats: String) = Json.parseToJsonElement(
            """{"solver":"scip","budgetMs":60000,"feasible":true,"objective":5.0,"proven":false,"maximize":false,"stats":{$stats}}""",
        )

        val checked = References.of(record(""""solveTime":"1.5","referenceVersion":"scip|v","validation":"repaired","dualBound":"4.0""""))
        val unchecked = References.of(record(""""solveTime":"1.5""""))

        assertEquals(listOf("scip|v", "repaired", 4.0, false), listOf(checked?.version, checked?.validation, checked?.dualBound, checked?.stale))
        assertEquals(true, unchecked?.stale)
    }

    @Test
    fun `retired XCSP reference proofs cannot replace a registered undecided row`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val key = "xcsp3-cop-22to25" to "case"
        val image = "klause-xcsp3-cpsat:source-preserving-v2@sha256:${"0".repeat(64)}"
        val current = ref(null).copy(version = "$image|${References.XCSP_POLICY}")
        store.putReferences(listOf(key to current), "current")
        val legacy = ref(false, proven = true).copy(version = "$image|xcsp3-matrix-row-major-v1")

        val changed = store.putReferences(listOf(key to legacy), "legacy")

        assertEquals(0, changed)
        assertEquals(current, store.referenceRows(key.first, key.second).single())
    }

    @Test
    fun `XCSP identities without a pinned image remain stale`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val key = "xcsp3-csp-22to25" to "case"
        val incomplete = ref(false, proven = true).copy(version = References.XCSP_POLICY)

        store.putReferences(listOf(key to incomplete), "incomplete")

        assertEquals(true, store.referenceRows(key.first, key.second).single().stale)
        assertEquals(emptyMap(), store.references(listOf(key)))
    }

    @Test
    fun `opening a database retires legacy XCSP claims while preserving their facts`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        val store = Store(file)
        val old = "xcsp3-cop-22to25" to "legacy"
        val current = "xcsp3-cop-22to25" to "current"
        val unversioned = "xcsp3-csp-22to25" to "unversioned"
        val minizinc = "hakank" to "minizinc"
        val image = "klause-xcsp3-cpsat:source-preserving-v2@sha256:${"0".repeat(64)}"
        val legacy = ref(false, proven = true).copy(version = "$image|xcsp3-matrix-row-major-v1")
        val checked = ref(true, 5.0).copy(version = "$image|${References.XCSP_POLICY}")
        store.putReferences(listOf(old to legacy, current to checked, unversioned to ref(false, proven = true),
            minizinc to ref(true)), "preserved")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { it.execute("UPDATE reference_rows SET stale = 0") }
        }

        val reopened = Store(file)

        assertEquals(legacy.copy(stale = true), reopened.referenceRows(old.first, old.second).single())
        assertEquals(setOf(current, minizinc), reopened.references(listOf(old, current, unversioned, minizinc)).keys)
    }

    @Test
    fun `retired XCSP records arrive stale without changing their verdict`() {
        val record = Json.parseToJsonElement(
            """{"solver":"cp-sat","budgetMs":60000,"feasible":false,"proven":true,"stats":{"referenceVersion":"klause-xcsp3-cpsat:latest@sha256:${"0".repeat(64)}|xcsp3-matrix-row-major-v1"}}""",
        )

        val captured = References.of(record)!!

        assertEquals(true, captured.stale)
        assertEquals(false, captured.feasible)
        assertEquals(true, captured.proven)
    }

    @Test
    fun `a proof without a solve time is timed by the wall clock, and a rerun of it refreshes the stored row`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val key = "hakank" to "building_a_house_model"
        store.putReferences(listOf(key to ref(false, proven = true).copy(elapsedMs = 1000)), "old")
        val record = Json.parseToJsonElement(
            """{"solver":"cp-sat","budgetMs":1000,"feasible":false,"proven":true,"maximize":false,"elapsedMs":137,"stats":{"flatTime":"0.1"}}""",
        )

        val rerun = References.of(record)!!
        val changed = store.putReferences(listOf(key to rerun), "rerun")

        assertEquals(137L, rerun.elapsedMs)
        assertEquals(1, changed)
        assertEquals(137L, store.references(listOf(key)).getValue(key).elapsedMs)
    }

    @Test
    fun `a comparison charges a proof without a solve time its wall-clock time`() {
        val outcome = Outcome.of(Json.parseToJsonElement(
            """{"kind":"satisfy","feasible":false,"proven":true,"budgetMs":60000,"elapsedMs":137,"stats":{}}""",
        ))

        assertEquals(137L, outcome?.timeMs)
    }

    private fun sourced(problem: String, objective: Int, model: String?) = CaseResult(0, Status.DONE, Problem("s", problem, collection = "c"), "a", null,
        Json.parseToJsonElement("""{"kind":"optimize","maximize":false,"feasible":true,"objective":$objective,"timeToBestMs":5,"budgetMs":1000""" +
            (model?.let { ""","sourceHashes":{"model":"$it"}""" } ?: "") + "}"))

    private fun identity(model: String) = References.identity(Json.parseToJsonElement("""{"sourceHashes":{"model":"$model"}}"""))

    @Test
    fun `a source identity digests the sorted source hashes, and a record without any has none`() {
        val a = References.identity(Json.parseToJsonElement("""{"sourceHashes":{"model":"m","data":"d"}}"""))
        val b = References.identity(Json.parseToJsonElement("""{"sourceHashes":{"data":"d","model":"m"}}"""))

        assertEquals(a, b)
        assertEquals(64, a?.length)
        assertEquals(listOf<String?>(null, null), listOf("""{"sourceHashes":{}}""", """{"sourceHashes":null}""").map { References.identity(Json.parseToJsonElement(it)) })
        assertEquals(identity("m"), References.of(Json.parseToJsonElement(
            """{"solver":"chuffed","budgetMs":1000,"feasible":true,"objective":3,"sourceHashes":{"model":"m"}}"""))?.sourceIdentity)
    }

    @Test
    fun `a proven optimum on another model is no disagreement, shortfall or coverage, and an unbound one only meets an unbound run`() {
        val bacp9 = Reference("chuffed", false, 38.0, true, true, 5, 1000, sourceIdentity = identity("bacp-9"))
        val unbound = bacp9.copy(sourceIdentity = null)

        val other = References.compare(listOf("a"), listOf(sourced("bacp", 30, "bacp-24")), mapOf(("c" to "bacp") to bacp9))
        val legacy = References.compare(listOf("a"), listOf(sourced("bacp", 30, "bacp-24")), mapOf(("c" to "bacp") to unbound))
        val same = References.compare(listOf("a"), listOf(sourced("bacp", 30, "bacp-9")), mapOf(("c" to "bacp") to bacp9))
        val neither = References.compare(listOf("a"), listOf(sourced("bacp", 30, null)), mapOf(("c" to "bacp") to unbound))

        assertEquals(listOf(0, 0, 1, 1), listOf(other, legacy, same, neither).map { it.summaries.single().covered })
        assertEquals(listOf(0, 0, 1, 1), listOf(other, legacy, same, neither).map { it.disagreements.size })
    }

    @Test
    fun `reference rows on different models neither conflict nor set each other's proofs aside`() {
        val bacp9 = Reference("chuffed", false, 38.0, true, true, 5, 1000, sourceIdentity = identity("bacp-9"))
        val bacp24 = Reference("cp-sat", false, 30.0, true, false, 5, 1000, sourceIdentity = identity("bacp-24"))

        assertEquals(emptyList(), References.conflicts(listOf(bacp9, bacp24)))
        assertEquals(listOf(bacp9, bacp24), References.trusted(listOf(bacp9, bacp24)))
        assertEquals(1, References.conflicts(listOf(bacp9, bacp24.copy(sourceIdentity = bacp9.sourceIdentity))).size)
    }

    @Test
    fun `the store keeps a row's source, compares by the run's source, and selects by the newest bound one`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val key = "c" to "bacp"
        val legacy = Reference("chuffed", false, 38.0, true, true, 5, 1000)
        val bound = Reference("cp-sat", false, 30.0, true, true, 5, 1000, sourceIdentity = identity("bacp-24"))
        store.putReferences(listOf(key to legacy, key to bound), "test")

        assertEquals(bound, store.references(listOf(key)).getValue(key))
        assertEquals(bound, store.references(listOf(key), sources = References.sources(listOf(sourced("bacp", 30, "bacp-24"))))[key])
        assertEquals(legacy, store.references(listOf(key), sources = References.sources(listOf(sourced("bacp", 30, null))))[key])
        assertEquals(null, store.references(listOf(key), sources = References.sources(listOf(sourced("bacp", 30, "bacp-19"))))[key])
        assertEquals(emptyList(), store.referenceConflicts())
        assertEquals(setOf(null, bound.sourceIdentity), store.referenceRows(key.first, key.second).map { it.sourceIdentity }.toSet())
    }

    @Test
    fun `a row bound to new source files replaces a stronger one on others, and an unbound one never replaces a bound one`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val key = "c" to "bacp"
        val proven = Reference("chuffed", false, 38.0, true, true, 5, 1000)
        val rebound = proven.copy(objective = 30.0, proven = false, sourceIdentity = identity("bacp-24"))
        store.putReferences(listOf(key to proven), "old")

        val replaced = store.putReferences(listOf(key to rebound), "new")
        val kept = store.putReferences(listOf(key to proven), "old again")

        assertEquals(listOf(1, 0), listOf(replaced, kept))
        assertEquals(listOf(rebound), store.referenceRows(key.first, key.second))
    }
}
