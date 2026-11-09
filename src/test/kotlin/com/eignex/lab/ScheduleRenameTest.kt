package com.eignex.lab

import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.web.api
import io.ktor.client.request.*
import io.ktor.http.*
import java.nio.file.Files
import kotlin.test.*

class ScheduleRenameTest {
    @Test fun `HTTP schedule update accepts a rename without changing its experiment controls`() = io.ktor.server.testing.testApplication {
        val config = Config(dataDir = Files.createTempDirectory("rename-api"), auth = Auth.Disabled)
        val store = Store(config.database)
        val spec = ExperimentSpec("koblas-status-sweep", priority = 98, bench = BenchSpec("bench.json", runTimeoutSec = 600))
        val id = store.schedules.create(spec.name, "main", spec, 120)
        application { api(config, store, HostReport("fixture", 4, "java")) }
        val response = client.post("/schedules/$id") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"koblas-sweep"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(spec.copy(name = "koblas-sweep"), store.schedules.all().single().experiment)
    }

    @Test fun `rename keeps schedule identity cadence and the complete run and confirmation history`() {
        val store = Store(Files.createTempDirectory("schedule-rename").resolve("lab.db"))
        val spec = ExperimentSpec("status-sweep", priority = 100, bench = BenchSpec("bench.json"))
        val schedule = store.schedules.create(spec.name, "main", spec, 120)
        val runName = "status-sweep@abc"
        val run = store.create(runName, "abc", emptyList(), experiment = spec.copy(name = runName))
        val confirmationName = "status-sweep~confirm@abc-1"
        val confirmation = store.create(confirmationName, "abc", emptyList(), experiment = spec.copy(name = confirmationName))
        val submitted = store.create("unrelated", "abc", emptyList(), experiment = spec.copy(name = "unrelated"))
        store.schedules.ran(schedule, "abc", run)
        store.schedules.checked(schedule, 1000)
        val before = store.schedules.all().single()

        assertTrue(store.schedules.rename(schedule, "klause-sweep"))

        val after = store.schedules.all().single()
        assertEquals(before.copy(name = "klause-sweep", experiment = spec.copy(name = "klause-sweep")), after)
        assertEquals(JobOrigin.Scheduled("klause-sweep"), store.job(run)!!.origin)
        assertEquals("klause-sweep@abc", store.job(run)!!.name)
        assertEquals("klause-sweep@abc", store.job(run)!!.experiment.name)
        assertEquals(JobOrigin.Confirmation("klause-sweep"), store.job(confirmation)!!.origin)
        assertEquals("klause-sweep~confirm@abc-1", store.job(confirmation)!!.name)
        assertEquals("unrelated", store.job(submitted)!!.name)
        assertTrue(store.schedules.rename(schedule, "klause-sweep"))
    }

    @Test fun `conflicting and reserved names leave both schedules unchanged`() {
        val store = Store(Files.createTempDirectory("schedule-rename").resolve("lab.db"))
        val first = store.schedules.create("first", "main", ExperimentSpec("first"), 120)
        store.schedules.create("second", "main", ExperimentSpec("second"), 120)
        val before = store.schedules.all()
        for (name in listOf("second", "", "bad@name", "bad~name")) {
            assertFailsWith<IllegalArgumentException> { store.schedules.rename(first, name) }
            assertEquals(before, store.schedules.all())
        }
        assertFalse(store.schedules.rename(9999, "missing"))
    }

    @Test fun `sweeps appear first and second by priority regardless of their creation order`() {
        val store = Store(Files.createTempDirectory("schedule-order").resolve("lab.db"))
        val klause = store.schedules.create("klause-sweep", "main", ExperimentSpec("klause-sweep", priority = 100), 120)
        val reference = store.schedules.create("reference", "main", ExperimentSpec("reference", priority = -2), 86400)
        val koblas = store.schedules.create("koblas-sweep", "main", ExperimentSpec("koblas-sweep", priority = 98), 120)
        assertEquals(listOf(klause, koblas, reference), store.schedules.all().map { it.id })
    }
}
