package com.eignex.lab

import com.eignex.lab.checker.LabCheck
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DbCompactionTest {
    private val host = HostReport("Test/Arm64", 4, "java")

    /** A build provenance like the bench's: tens of kilobytes, one per arm here, with numbers and text kept as written. */
    private fun provenance(arm: Int, kib: Int = 4): String {
        val files = (0 until kib * 8).joinToString(",") { "\"lib/dep-$it.jar\":\"${"%064x".format(arm * 100_000 + it)}\"" }
        return """{"distribution":{$files},"runtime":"OpenJDK 21.0.4+7 ü","runtimeOptions":{"KLAUSE_CLI_OPTS":"-Xmx3g"},
            "osName":"Mac OS X","osArch":"aarch64","cores":1.0,"seed":1e3,"arm":$arm,"empty":{},"none":null}"""
    }

    /** The producer record an SMT or MPS check keeps, pretty-printed as the bench writes it, as a JSON string. */
    private fun producer(timeToBest: Long?, kib: Int = 2): String {
        val text = "{\n    \"problem\": \"p\",\n" + (timeToBest?.let { "    \"timeToBestMs\": $it,\n" } ?: "") +
            "    \"finalWitness\": \"${"x".repeat(kib * 1024)}\",\n    \"stats\": {\"exit\": \"0\"}\n}"
        return JsonPrimitive(text).toString()
    }

    /** A case record as the bench writes it, pretty-printed; [withdrawn] moves its claim aside as a failed check does. */
    private fun record(index: Int, arm: Int, withdrawn: Boolean = false, producer: String? = null, kib: Int = 4): String {
        val stats = buildList {
            add("\"solveTime\": \"9.5\"")
            if (withdrawn) {
                add("\"sourceValidation\": \"unknown\"")
                add("\"reportedFeasible\": \"true\"")
                add("\"reportedObjective\": \"81.0\"")
                add("\"reportedProven\": \"false\"")
            }
            producer?.let { add("\"sourceValidationProducerRecordJson\": $it") }
            add("\"sourceValidationRecordSha256\": \"${"r".repeat(64)}\"")
        }.joinToString(",\n    ")
        return """{
  "problem": "p$index",
  "solver": "klause",
  "kind": "optimize",
  "feasible": ${if (withdrawn) "null" else "true"},
  "objective": ${if (withdrawn) "null" else "81.0"},
  "proven": false,
  "timeToBestMs": ${if (withdrawn) "null" else "40"},
  "budgetMs": 10000,
  "buildProvenance": ${provenance(arm, kib)},
  "finalWitness": "(model (define-fun x () Int $index))",
  "sourceHashes": {"model": "${"s".repeat(64)}"},
  "attribution": {"arm": "a$arm"},
  "stats": {
    $stats
  }
}"""
    }

    private val check = LabCheck(policy = "lab-test-v1", state = LabCheck.VALID, reason = "accepted", checker = "test").json()

    /** An experiment of [problems] problems on [arms] arms, without records. */
    private fun experiment(store: Store, problems: Int, arms: Int): Long {
        val id = store.create("compact", "main", emptyList(), experiment = ExperimentSpec("compact", listOf(mapOf("suite" to "s"))))
        store.plan(id, (0 until arms).map { PlannedArm(Arm("a$it", emptyMap()), "abc") }, (0 until problems).map { Problem("s", "p$it") },
            Experiments.cases(problems, arms, emptyList()), List(problems * arms) { "true" to 1L })
        return id
    }

    /** Each case's record as the bench wrote it: case i is arm i % arms; every third has a withdrawn claim. */
    private fun records(store: Store, id: Long, kib: Int = 4): Map<Int, String> {
        val arms = store.arms(id).size
        return store.casePlan(id).associate { case ->
            val arm = store.arms(id).indexOfFirst { it.arm.label == case.arm }
            val withdrawn = case.index % 3 == 0
            case.index to record(case.index, arm, withdrawn, if (case.index % 2 == 0) producer(if (withdrawn) 7000L + case.index else 5L, kib) else null, kib)
                .also { check(arms > 0) }
        }
    }

    /** Write [records] straight into the database, as a lab before the compaction stored them. */
    private fun storeRaw(file: Path, id: Long, records: Map<Int, String>) =
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.prepareStatement("UPDATE cases SET record = ? WHERE job_id = ? AND idx = ?").use { s ->
                for ((index, record) in records) {
                    s.setString(1, record)
                    s.setLong(2, id)
                    s.setInt(3, index)
                    s.executeUpdate()
                }
            }
        }

    private fun storedText(file: Path, id: Long, index: Int): String = DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
        c.prepareStatement("SELECT record FROM cases WHERE job_id = ? AND idx = ?").use { s ->
            s.setLong(1, id)
            s.setInt(2, index)
            s.executeQuery().use { r -> r.next(); r.getString(1) }
        }
    }

    /** [record] as the lab reads it once stored: its producer record cut to its time to best, all else as written. */
    private fun expected(record: String): JsonElement {
        val parsed = Json.parseToJsonElement(record).jsonObject
        val stats = parsed.getValue("stats").jsonObject
        val text = (stats["sourceValidationProducerRecordJson"] as? JsonPrimitive)?.content ?: return parsed
        val time = Json.parseToJsonElement(text).jsonObject["timeToBestMs"]
        val trimmed = JsonObject(if (time != null) mapOf("timeToBestMs" to time) else emptyMap()).toString()
        return JsonObject(parsed + ("stats" to JsonObject(stats + ("sourceValidationProducerRecordJson" to JsonPrimitive(trimmed)))))
    }

    private fun json(element: JsonElement?) = Json.encodeToString(JsonElement.serializer(), element!!)

    @Test
    fun `a record reads back whole, its provenance kept once, in the pages and every export`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
        val store = Store(config.database)
        val id = experiment(store, problems = 6, arms = 2)
        val records = records(store, id)
        for ((index, record) in records) store.caseRecord(id, index, record)
        store.caseCheck(id, 1, check)

        val stored = storedText(config.database, id, 1)
        assertContains(stored, "\"buildProvenanceRef\":\"")
        assertFalse("\"buildProvenance\"" in stored)
        assertEquals(2, store.database().provenances)
        for (case in store.cases(id)) {
            // Byte for byte as the bench wrote it, once serialized as every reader serializes it.
            val want = withLabCheck(expected(records.getValue(case.index)), if (case.index == 1) check else null)
            assertEquals(json(want), json(case.record), "case ${case.index}")
        }
        assertEquals(json(Json.parseToJsonElement(records.getValue(1))), json(Json.parseToJsonElement(store.caseRecordText(id, 1)!!)))
        assertEquals(store.cases(id), store.cases(id, 0, 100))
        testApplication {
            application { api(config, store, host) }
            val array = Json.parseToJsonElement(client.get("/experiments/$id/cases").bodyAsText()).jsonArray
            val lines = client.get("/experiments/$id/cases.jsonl").bodyAsText().lines().filter { it.isNotEmpty() }.map(Json::parseToJsonElement)
            assertEquals(lines, array.toList())
            for (line in lines) {
                val index = line.jsonObject.getValue("index").jsonPrimitive.content.toInt()
                val want = withLabCheck(expected(records.getValue(index)), if (index == 1) check else null)
                assertEquals(json(want), json(line.jsonObject.getValue("record")))
            }
        }
    }

    @Test
    fun `the producer record is cut to its time to best, which a withdrawn claim still gets back`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val id = experiment(store, problems = 1, arms = 1)
        store.caseRecord(id, 0, record(0, 0, withdrawn = true, producer = producer(7000)))

        val stats = store.cases(id).single().record!!.jsonObject.getValue("stats").jsonObject
        assertEquals("{\"timeToBestMs\":7000}", stats.getValue("sourceValidationProducerRecordJson").jsonPrimitive.content)
        assertEquals("r".repeat(64), stats.getValue("sourceValidationRecordSha256").jsonPrimitive.content)
        assertEquals(7000L, Outcome.of(store.cases(id).single().record)!!.timeToBestMs)
        assertEquals(7000L, Outcome.of(store.scoringCases(id).single().record)!!.timeToBestMs)
        assertTrue(Outcome.withdrawn(store.cases(id).single().record!!.jsonObject))
    }

    @Test
    fun `the migration compacts old records, resumes mid-way, and changes nothing done again`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        var store = Store(file)
        val id = experiment(store, problems = 10, arms = 3)
        val records = records(store, id)
        storeRaw(file, id, records)
        store.caseCheck(id, 4, check)
        val scoring = store.scoringCases(id).map { it.copy(record = it.record) }
        val outcomes = store.cases(id).map { Outcome.of(it.record) }

        store.startCompaction()
        val partway = store.compactBatch(7)
        assertEquals(7, partway.scanned)
        assertNull(partway.migratedAt)
        assertTrue("\"buildProvenance\"" in storedText(file, id, 20))

        store = Store(file) // a restart
        val done = DbCompaction(store, { Long.MAX_VALUE }, { false }, log = {}, pauseMs = 0).migrate()
        assertEquals(30, done.scanned)
        assertEquals(30, done.changed)
        assertNotNull(done.migratedAt)
        assertTrue(done.bytesAfter < done.bytesBefore / 2)
        assertEquals(3, store.database().provenances)
        for (case in store.cases(id)) {
            val want = withLabCheck(expected(records.getValue(case.index)), if (case.index == 4) check else null)
            assertEquals(json(want), json(case.record), "case ${case.index}")
        }
        // Scoring reads exactly what it read before.
        assertEquals(scoring, Store(file).scoringCases(id))
        assertEquals(outcomes, store.cases(id).map { Outcome.of(it.record) })

        // Done again from the start, it finds nothing to change.
        val stored = (0 until 30).map { storedText(file, id, it) }
        DriverManager.getConnection("jdbc:sqlite:$file").use { it.createStatement().execute("DELETE FROM compaction") }
        val again = DbCompaction(store, { Long.MAX_VALUE }, { false }, log = {}, pauseMs = 0).migrate()
        assertEquals(30, again.scanned)
        assertEquals(0, again.changed)
        assertEquals(stored, (0 until 30).map { storedText(file, id, it) })
        assertEquals(again, DbCompaction(store, { Long.MAX_VALUE }, { false }, log = {}, pauseMs = 0).migrate())
    }

    @Test
    fun `a record written during the migration is left as its writer stored it`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        val store = Store(file)
        val id = experiment(store, problems = 2, arms = 1)
        val records = records(store, id)
        storeRaw(file, id, records)
        store.startCompaction()
        val newer = record(0, 0, kib = 1).replace("\"p0\"", "\"p0-again\"")
        store.caseRecord(id, 0, newer)
        store.compactBatch(10)
        assertEquals(json(expected(newer)), json(store.cases(id)[0].record))
    }

    @Test
    fun `new records are written compacted and imports too`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        val store = Store(file)
        val spec = ExperimentSpec("imported", listOf(mapOf("suite" to "s")))
        val id = store.importExperiment("imported", "main", "abc", spec, listOf(PlannedArm(Arm("a0", emptyMap()), "abc")),
            listOf(Problem("s", "p0")), listOf(Case(0, 0, null) to record(0, 0, producer = producer(3))), listOf("true"))
        val stored = storedText(file, id, 0)
        assertContains(stored, "buildProvenanceRef")
        assertContains(stored, "{\\\"timeToBestMs\\\":3}")
        assertEquals(json(expected(record(0, 0, producer = producer(3)))), json(store.cases(id).single().record))
    }

    @Test
    fun `a synthetic database shrinks to a fraction once compacted and vacuumed`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        val store = Store(file)
        val id = experiment(store, problems = 100, arms = 4)
        val records = records(store, id, kib = 40)
        storeRaw(file, id, records)
        DriverManager.getConnection("jdbc:sqlite:$file").use { it.createStatement().execute("PRAGMA wal_checkpoint(TRUNCATE)") }
        val before = store.database()
        val compaction = DbCompaction(store, { Long.MAX_VALUE }, { false }, log = {}, pauseMs = 0, vacuumMinFreeBytes = 0)
        compaction.migrate()
        assertFalse(DbCompaction(store, { 0 }, { false }, log = {}, vacuumMinFreeBytes = 0).maybeVacuum())
        assertContains(store.compaction().vacuum!!, "skipped")
        assertFalse(DbCompaction(store, { Long.MAX_VALUE }, { true }, log = {}, vacuumMinFreeBytes = 0).maybeVacuum())

        assertTrue(compaction.maybeVacuum())
        val after = store.database()
        assertTrue(after.bytes < before.bytes / 5, "${before.bytes} → ${after.bytes}")
        assertTrue(Files.size(file) < before.bytes / 5)
        assertTrue(after.freeBytes < after.bytes / 10)
        assertFalse(Files.exists(store.vacuumMarker))
        assertContains(store.compaction().vacuum!!, "MB →")
        for (case in store.cases(id)) assertEquals(json(expected(records.getValue(case.index))), json(case.record))
    }

    @Test
    fun `a write waits for a vacuum in another process, and only for it`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        val store = Store(file, busyTimeoutMs = 100)
        val id = store.create("j", "main", listOf("true" to 1L))
        fun holdLock(ms: Long) = DriverManager.getConnection("jdbc:sqlite:$file").let { c ->
            c.createStatement().execute("BEGIN IMMEDIATE")
            thread { Thread.sleep(ms); c.createStatement().execute("COMMIT"); c.close() }
        }

        Files.writeString(store.vacuumMarker, "now")
        val holder = holdLock(600)
        assertTrue(store.setPriority(id, 3))
        holder.join()
        Files.delete(store.vacuumMarker)

        val other = holdLock(1500)
        assertFailsWith<SQLException> { store.setPriority(id, 4) }
        other.join()
    }

    @Test
    fun `the disk page shows the database and the compaction's progress`() {
        val database = DatabaseReport(4096, 1000, 250, 3, Compaction(total = 200, scanned = 50, changed = 40, bytesBefore = 4_000_000,
            bytesAfter = 1_000_000, startedAt = 1))
        val page = diskPage(null, database = database)
        assertContains(page, "<h2>Database</h2>")
        assertContains(page, "50 of 200 case records (25%)")
        assertContains(page, "3 distinct build provenances")
    }
}
