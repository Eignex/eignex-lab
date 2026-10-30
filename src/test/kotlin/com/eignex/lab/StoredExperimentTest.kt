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

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StoredExperimentTest {
    @Test fun `a stored experiment is one kind of work`() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(Work.Recipe, json.decodeFromString<ExperimentSpec>("{\"name\":\"old\",\"problems\":[{\"suite\":\"s\"}]}").work)
        assertEquals(Work.Script("echo", null), json.decodeFromString<ExperimentSpec>("{\"name\":\"old\",\"script\":\"echo\"}").work)
        assertEquals(Work.Analysis(AnalyzeSpec(job = 3)), ExperimentSpec("a", analyze = AnalyzeSpec(job = 3)).work)
        assertEquals(Work.Bench(BenchSpec("bench.json")), ExperimentSpec("b", bench = BenchSpec("bench.json")).work)
        assertEquals(Work.Profile(ProfileMain("Main")), ExperimentSpec("p", profile = ProfileMain("Main")).work)
        assertFailsWith<IllegalArgumentException> { ExperimentSpec("x", bench = BenchSpec("bench.json"), profile = ProfileMain("Main")) }
        assertFailsWith<IllegalArgumentException> { ExperimentSpec("x", analyze = AnalyzeSpec(job = 3), script = "echo") }
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString<ExperimentSpec>("{\"name\":\"x\",\"bench\":{\"manifest\":\"bench.json\"},\"analyze\":{\"job\":3}}")
        }
    }

    @Test fun `retired recording settings preserve the historical measurement mode`() {
        val cases = mapOf(
            "\"measureCliResources\":true" to Measurement.RESOURCES,
            "\"measureCliResources\":false,\"profileCli\":false,\"profileCompilation\":null" to Measurement.NONE,
            "\"profileCli\":true" to Measurement.JFR,
            "\"profileCli\":true,\"profileCompilation\":\"jfr\"" to Measurement.COMPILATION,
            "\"profileCli\":true,\"profileCompilation\":\"jfr+log\"" to Measurement.COMPILATION_LOG,
            "\"measureCliResources\":true,\"measurement\":\"jfr\"" to Measurement.JFR,
        )
        for ((fields, expected) in cases) {
            val decoded = storedExperiment("""{"name":"historical",$fields}""")
            assertEquals(expected, decoded.measurement)
            assertEquals(decoded, Json.decodeFromString<ExperimentSpec>(Json.encodeToString(decoded)))
        }
        assertFailsWith<IllegalArgumentException> { storedExperiment("""{"name":"bad","measureCliResources":true,"typo":true}""") }
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString<ExperimentSpec>("""{"name":"new","measureCliResources":true}""") }
    }

    @Test fun `history and schedules load recording settings from older database records`() {
        val file = Files.createTempDirectory("historical measurements").resolve("lab.db")
        val store = Store(file)
        val spec = ExperimentSpec("historical")
        val job = store.create(spec.name, "main", emptyList(), experiment = spec)
        val schedule = store.schedules.create("nightly", "main", spec, 3600)
        val old = """{"name":"historical","measureCliResources":true}"""
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            for ((table, id) in listOf("jobs" to job, "schedules" to schedule)) {
                connection.prepareStatement("UPDATE $table SET experiment = ? WHERE id = ?").use {
                    it.setString(1, old); it.setLong(2, id); it.executeUpdate()
                }
            }
        }
        assertEquals(Measurement.RESOURCES, store.job(job)!!.experiment!!.measurement)
        assertEquals(Measurement.RESOURCES, store.active().single().experiment!!.measurement)
        assertEquals(Measurement.RESOURCES, store.schedules.all().single().experiment.measurement)
        assertEquals(Measurement.RESOURCES, Store(file).job(job)!!.experiment!!.measurement)
    }
}
