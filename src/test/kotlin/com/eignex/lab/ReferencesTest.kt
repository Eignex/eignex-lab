package com.eignex.lab

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class ReferencesTest {
    private fun ref(feasible: Boolean?, objective: Double? = null, proven: Boolean = false) =
        Reference("cp-sat", maximize = false, objective = objective, feasible = feasible, proven = proven, elapsedMs = 10, budgetMs = 1000)

    @Test
    fun `a re-import keeps the stronger result already stored`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val key = ProblemKey("c", "p")
        store.references.put(listOf(key to ref(true, 5.0, proven = true)), "a")

        val changed = store.references.put(listOf(key to ref(true, 9.0)), "b")

        assertEquals(0 to 5.0, changed to store.references.matching(listOf(key)).getValue(key).objective)
    }

    @Test
    fun `an arm proving infeasible what the reference solved is a disagreement`() {
        val case = CaseResult(0, Status.DONE, Problem("s", "p", collection = "c"), "a", null,
            Json.parseToJsonElement("""{"kind":"satisfy","feasible":false,"proven":true,"budgetMs":1000}"""))

        val comparison = References.compare(listOf("a"), listOf(case), mapOf(ProblemKey("c", "p") to ref(true)))

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
            mapOf(ProblemKey("c", "close") to proven, ProblemKey("c", "far") to proven, ProblemKey("c", "equal") to proven),
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
        store.references.put(
            listOf(
                ProblemKey("c", "a") to ref(true, 1.0, proven = true),
                ProblemKey("c", "b") to ref(false, proven = true),
                ProblemKey("c", "c") to ref(null),
                ProblemKey("d", "a") to ref(true, 2.0, proven = true).copy(solver = "z3"),
            ),
            "test",
        )

        val (rows, total) = store.references.search(ReferenceFilter(solver = "cp-sat", verdict = ReferenceVerdict.OPTIMUM), limit = 10)

        assertEquals(listOf(ProblemKey("c", "a")) to 1, rows.map { it.first } to total)
    }

    @Test
    fun `a problem's runs come from every experiment that ran it`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val problems = listOf(Problem("s", "p", collection = "c"), Problem("s", "q", collection = "c"))
        val id = store.create("e", "main", emptyList(), experiment = ExperimentSpec("e", listOf(mapOf("suite" to "s"))))
        store.plan(id, listOf(PlannedArm(Arm("base", emptyMap()), "sha")), problems, Experiments.cases(2, 1, emptyList()), listOf("true" to 1L, "true" to 1L))

        val runs = store.problemRuns("c", "q")

        assertEquals(listOf(id to 1), runs.map { it.job to it.case })
        // A second job's case of q comes first, and a limit keeps only the newest.
        val next = store.create("f", "main", emptyList(), experiment = ExperimentSpec("f", listOf(mapOf("suite" to "s"))))
        store.plan(next, listOf(PlannedArm(Arm("base", emptyMap()), "sha")), problems, Experiments.cases(2, 1, emptyList()), listOf("true" to 1L, "true" to 1L))
        assertEquals(listOf(next to 1), store.problemRuns("c", "q", limit = 1).map { it.job to it.case })
        assertEquals(2, store.problemRunCount("c", "q"))
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
        val key = ProblemKey("klause-bench/smoke-corpus/qfnra-v1/core/algebraic", "algebraic/irrational-tower")
        val record = Json.parseToJsonElement("""{"solver":"z3","kind":"satisfy","feasible":true,"proven":true,
            "budgetMs":10000,"command":"z3 -model input.smt2","stats":{"solveTime":"0.006",
            "referenceVersion":"Z3 pinned|sha256=abc|smt-reference-v2","referenceStatus":"sat"}}""")
        val row = requireNotNull(References.of(record))

        store.references.put(listOf(key to row), "lab:1")
        val stored = store.references.matching(listOf(key), "z3")

        assertEquals(row, stored[key])
        assertEquals("Z3 pinned|sha256=abc|smt-reference-v2", store.references.rows(key.collection, key.problem).single().version)
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
        val key = ProblemKey("miplib2017", "neos4")
        store.references.put(listOf(key to mps("highs", true, -48454383704.3946, proven = true, version = "")), "old")
        val corrected = mps("highs", true, -48603440750.5895, proven = false)

        val replaced = store.references.put(listOf(key to corrected), "new")
        val unchecked = store.references.put(listOf(key to mps("highs", true, -48454383704.3946, proven = true, version = "")), "old bench")

        assertEquals(listOf(1, 0), listOf(replaced, unchecked))
        assertEquals(corrected, store.references.matching(listOf(key), "highs")[key])
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
        val key = ProblemKey("miplib2017", "neos-1603965")
        store.references.put(listOf(key to mps("scip", true, 637213553.1165, proven = false)), "scip")
        store.references.put(listOf(key to mps("highs", false, null, proven = true)), "highs")
        val stale = ProblemKey("miplib2017", "marne")
        store.references.put(listOf(stale to mps("scip", true, 317056.2, proven = true, version = "")), "old")

        val trusted = store.references.matching(listOf(key, stale))

        assertEquals(listOf("scip" to true), trusted.values.map { it.solver to it.feasible })
        assertEquals(2, store.references.rows(key.collection, key.problem).size)
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

        assertEquals(listOf(true, false), listOf("miplib2017" to "neos4", "hakank" to "q").map { store.references.rows(it.first, it.second).single().stale })
        assertEquals(setOf(ProblemKey("hakank", "q")), store.references.matching(listOf(ProblemKey("miplib2017", "neos4"), ProblemKey("hakank", "q"))).keys)
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
        val key = ProblemKey("xcsp3-cop-22to25", "case")
        val image = "klause-xcsp3-cpsat:source-preserving-v2@sha256:${"0".repeat(64)}"
        val current = ref(null).copy(version = "$image|${References.XCSP_POLICY}")
        store.references.put(listOf(key to current), "current")
        val legacy = ref(false, proven = true).copy(version = "$image|xcsp3-matrix-row-major-v1")

        val changed = store.references.put(listOf(key to legacy), "legacy")

        assertEquals(0, changed)
        assertEquals(current, store.references.rows(key.collection, key.problem).single())
    }

    @Test
    fun `XCSP identities without a pinned image remain stale`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val key = ProblemKey("xcsp3-csp-22to25", "case")
        val incomplete = ref(false, proven = true).copy(version = References.XCSP_POLICY)

        store.references.put(listOf(key to incomplete), "incomplete")

        assertEquals(true, store.references.rows(key.collection, key.problem).single().stale)
        assertEquals(emptyMap(), store.references.matching(listOf(key)))
    }

    @Test
    fun `the runner retires legacy XCSP claims while preserving their facts`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        val store = Store(file)
        val old = ProblemKey("xcsp3-cop-22to25", "legacy")
        val current = ProblemKey("xcsp3-cop-22to25", "current")
        val unversioned = ProblemKey("xcsp3-csp-22to25", "unversioned")
        val minizinc = ProblemKey("hakank", "minizinc")
        val image = "klause-xcsp3-cpsat:source-preserving-v2@sha256:${"0".repeat(64)}"
        val legacy = ref(false, proven = true).copy(version = "$image|xcsp3-matrix-row-major-v1")
        val checked = ref(true, 5.0).copy(version = "$image|${References.XCSP_POLICY}")
        store.references.put(listOf(old to legacy, current to checked, unversioned to ref(false, proven = true),
            minizinc to ref(true)), "preserved")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { it.execute("UPDATE reference_rows SET stale = 0") }
        }

        val reopened = Store(file)
        reopened.references.retireStale()

        assertEquals(legacy.copy(stale = true), reopened.references.rows(old.collection, old.problem).single())
        assertEquals(setOf(current, minizinc), reopened.references.matching(listOf(old, current, unversioned, minizinc)).keys)
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
        val key = ProblemKey("hakank", "building_a_house_model")
        store.references.put(listOf(key to ref(false, proven = true).copy(elapsedMs = 1000)), "old")
        val record = Json.parseToJsonElement(
            """{"solver":"cp-sat","budgetMs":1000,"feasible":false,"proven":true,"maximize":false,"elapsedMs":137,"stats":{"flatTime":"0.1"}}""",
        )

        val rerun = References.of(record)!!
        val changed = store.references.put(listOf(key to rerun), "rerun")

        assertEquals(137L, rerun.elapsedMs)
        assertEquals(1, changed)
        assertEquals(137L, store.references.matching(listOf(key)).getValue(key).elapsedMs)
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
    fun `a known source requires a bound reference for coverage and disagreements`() {
        val bacp9 = Reference("chuffed", false, 38.0, true, true, 5, 1000, version = "chuffed-fixture", sourceIdentity = identity("bacp-9"))
        val unbound = bacp9.copy(sourceIdentity = null)

        val other = References.compare(listOf("a"), listOf(sourced("bacp", 30, "bacp-24")), mapOf(ProblemKey("c", "bacp") to bacp9))
        val legacy = References.compare(listOf("a"), listOf(sourced("bacp", 30, "bacp-24")), mapOf(ProblemKey("c", "bacp") to unbound))
        val same = References.compare(listOf("a"), listOf(sourced("bacp", 30, "bacp-9")), mapOf(ProblemKey("c", "bacp") to bacp9))
        val neither = References.compare(listOf("a"), listOf(sourced("bacp", 30, null)), mapOf(ProblemKey("c", "bacp") to unbound))

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
        val key = ProblemKey("c", "bacp")
        val legacy = Reference("chuffed", false, 38.0, true, true, 5, 1000)
        val bound = Reference("cp-sat", false, 30.0, true, true, 5, 1000, version = "cp-sat-fixture", sourceIdentity = identity("bacp-24"))
        store.references.put(listOf(key to legacy, key to bound), "test")

        assertEquals(bound, store.references.matching(listOf(key)).getValue(key))
        assertEquals(bound, store.references.matching(listOf(key), sources = References.sources(listOf(sourced("bacp", 30, "bacp-24"))))[key])
        assertEquals(legacy, store.references.matching(listOf(key), sources = References.sources(listOf(sourced("bacp", 30, null))))[key])
        assertEquals(null, store.references.matching(listOf(key), sources = References.sources(listOf(sourced("bacp", 30, "bacp-19"))))[key])
        assertEquals(emptyList(), store.references.conflicts())
        assertEquals(setOf(null, bound.sourceIdentity), store.references.rows(key.collection, key.problem).map { it.sourceIdentity }.toSet())
    }

    @Test
    fun `a row bound to new source files replaces a stronger one on others, and an unbound one never replaces a bound one`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val key = ProblemKey("c", "bacp")
        val proven = Reference("chuffed", false, 38.0, true, true, 5, 1000)
        val rebound = proven.copy(objective = 30.0, proven = false, sourceIdentity = identity("bacp-24"))
        store.references.put(listOf(key to proven), "old")

        val replaced = store.references.put(listOf(key to rebound), "new")
        val kept = store.references.put(listOf(key to proven), "old again")

        assertEquals(listOf(1, 0), listOf(replaced, kept))
        assertEquals(listOf(rebound), store.references.rows(key.collection, key.problem))
    }

    @Test
    fun `an unbound row cannot establish the reference for a known source`() {
        val unbound = ref(true, 5.0, proven = true)
        val nine = unbound.copy(solver = "chuffed", sourceIdentity = "bacp-9")
        val twentyFour = unbound.copy(solver = "chuffed", version = "chuffed-fixture", sourceIdentity = "bacp-24")

        assertEquals(emptyList(), References.applicable(listOf(unbound), "bacp-24"))
        assertEquals(listOf(twentyFour), References.applicable(listOf(twentyFour, unbound), "bacp-24"))
        assertEquals(emptyList(), References.applicable(listOf(nine, unbound), "bacp-24"))
        assertEquals(listOf(unbound), References.applicable(listOf(nine, unbound), null))
        assertEquals(emptyList(), References.applicable(listOf(unbound), References.MIXED_SOURCES))
    }

    @Test
    fun `a source bound row without a reference version cannot cover a known source`() {
        val row = ref(true, 5.0, proven = true).copy(sourceIdentity = "bacp-24")

        for (version in listOf("", " ", "unknown", "null", "unavailable")) {
            assertEquals(emptyList(), References.applicable(listOf(row.copy(version = version)), "bacp-24"))
        }
    }

    @Test
    fun `rows a lab run made learn the source files its cases recorded`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val problem = Problem("s", "p", collection = "c")
        val id = store.create("ref", "main", emptyList(), experiment = ExperimentSpec("ref", listOf(mapOf("suite" to "s"))))
        store.startForTest()
        store.plan(id, listOf(PlannedArm(Arm("cp-sat", emptyMap()), "sha")), listOf(problem), Experiments.cases(1, 1, emptyList()), listOf("true" to 1L))
        store.caseRecord(id, 0, """{"kind":"satisfy","feasible":true,"sourceHashes":{"model":"m1"}}""")
        store.finish(id, Status.DONE)
        store.references.put(listOf(ProblemKey("c", "p") to ref(true, proven = true)), "lab:$id")
        store.references.put(listOf(ProblemKey("c", "q") to ref(true, proven = true).copy(solver = "z3")), "import")

        assertEquals(1, store.references.bindLabSources(store.plans::scoringCases))
        val identity = References.identity(Json.parseToJsonElement("""{"sourceHashes":{"model":"m1"}}"""))
        assertEquals(identity, store.references.current(listOf(ProblemKey("c", "p"))).getValue(ProblemKey("c", "p")).single().sourceIdentity)
        assertEquals(null, store.references.current(listOf(ProblemKey("c", "q"))).getValue(ProblemKey("c", "q")).single().sourceIdentity)
        assertEquals(0, store.references.bindLabSources(store.plans::scoringCases))
    }

    private fun kissat(feasible: Boolean?, budgetMs: Long, proven: Boolean = feasible != null) =
        Reference("kissat", maximize = false, objective = null, feasible = feasible, proven = proven, elapsedMs = 100, budgetMs = budgetMs)

    @Test
    fun `retiring a solver's rows of one budget marks only those stale and keeps them as history`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val long = ProblemKey("satcomp", "a")
        val short = ProblemKey("satcomp", "b")
        val other = ProblemKey("satlib", "a")
        store.references.put(listOf(long to kissat(true, 300_000), short to kissat(false, 60_000), other to kissat(true, 300_000)), "lab:1")
        store.references.put(listOf(long to Reference("clasp", false, null, true, true, 10, 300_000)), "lab:2")

        val retired = store.references.retire("satcomp", "kissat", 300_000)

        assertEquals(1, retired)
        assertEquals(0, store.references.retire("satcomp", "kissat", 300_000))
        assertEquals(listOf("clasp" to false, "kissat" to true), store.references.rows(long.collection, long.problem).map { it.solver to it.stale }.sortedBy { it.first })
        assertEquals(false, store.references.rows(short.collection, short.problem).single().stale)
        assertEquals(false, store.references.rows(other.collection, other.problem).single().stale)
        assertEquals(null, store.references.matching(listOf(long), "kissat")[long])
        assertEquals(2, store.references.retire("satcomp", "kissat") + store.references.retire("satlib", "kissat"))
    }

    @Test
    fun `the scoreboard, coverage and comparisons leave retired rows out`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val key = ProblemKey("satcomp", "a")
        store.references.put(listOf(key to kissat(true, 300_000), ProblemKey("satcomp", "b") to kissat(true, 60_000)), "lab:1")

        store.references.retire("satcomp", "kissat", 300_000)

        assertEquals(emptyMap(), store.references.current(listOf(key)))
        assertEquals(emptyMap(), store.references.matching(listOf(key)))
        assertEquals(listOf(1), store.references.coverage().filter { it.collection == "satcomp" && it.solver == "kissat" }.map { it.rows })
        assertEquals(true, ReferenceFilterMode.MISSING.keeps(store.references.matching(listOf(key), "kissat")[key], 60_000))
    }

    @Test
    fun `a later row of any budget or strength takes the place of a retired one`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val key = ProblemKey("satcomp", "a")
        val proof = kissat(true, 300_000)
        store.references.put(listOf(key to proof), "lab:1")
        // Before it is retired, a 60 s rerun with the same verdict never displaces the 300 s row.
        assertEquals(0, store.references.put(listOf(key to kissat(true, 60_000)), "lab:2"))

        store.references.retire("satcomp", "kissat", 300_000)
        val unknown = kissat(null, 60_000)

        assertEquals(true, References.replaces(unknown, proof.copy(stale = true)))
        assertEquals(false, References.replaces(proof.copy(stale = true), unknown))
        assertEquals(1, store.references.put(listOf(key to unknown), "lab:3"))
        assertEquals(unknown, store.references.matching(listOf(key), "kissat")[key])
        assertEquals(1, store.references.put(listOf(key to kissat(true, 60_000)), "lab:4"))
        assertEquals(60_000L, store.references.current(listOf(key)).getValue(key).single().budgetMs)
    }

    @Test
    fun `retiring over HTTP needs the password and a same-site request, and says how many rows it marked`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), auth = Auth.Required(Password.hash("secret")))
        val store = Store(config.database)
        store.references.put(listOf(ProblemKey("satcomp", "a") to kissat(true, 300_000), ProblemKey("satcomp", "b") to kissat(true, 60_000)), "lab:1")
        val auth = "Basic " + Base64.getEncoder().encodeToString(":secret".toByteArray())
        val body = """{"collection":"satcomp","solver":"kissat","budgetMs":300000}"""
        testApplication {
            application { api(config, store, HostReport("Test/Arm64", 4, "java")) }

            assertEquals(HttpStatusCode.Unauthorized, client.post("/references/retire") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }.status)
            assertEquals(HttpStatusCode.Forbidden, client.post("/references/retire") {
                header(HttpHeaders.Authorization, auth)
                header(HttpHeaders.Origin, "https://evil.example")
                contentType(ContentType.Application.Json)
                setBody(body)
            }.status)
            val retired = client.post("/references/retire") {
                header(HttpHeaders.Authorization, auth)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.OK, retired.status)
            assertEquals(1, Json.decodeFromString(RetireResult.serializer(), retired.bodyAsText()).retired)
            assertEquals(HttpStatusCode.BadRequest, client.post("/references/retire") {
                header(HttpHeaders.Authorization, auth)
                contentType(ContentType.Application.Json)
                setBody("""{"collection":"","solver":"kissat"}""")
            }.status)
        }
        assertEquals(listOf(true, false), listOf("a", "b").map { store.references.rows("satcomp", it).single().stale })
    }
}
