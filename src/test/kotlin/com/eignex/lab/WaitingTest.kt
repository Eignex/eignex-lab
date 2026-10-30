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

import java.nio.file.Files
import kotlin.test.*

class WaitingTest {
    @Test fun `placement reports Nomad waiting and paused work without legacy pool configuration`() {
        val config = Config(dataDir = Files.createTempDirectory("waiting"), minFreeBytes = 0)
        val store = Store(config.database)
        val id = store.create("queued", "main", emptyList(), experiment = ExperimentSpec("queued"))
        val report = Waiting.report(config.status, store, { null })
        assertContains(report.jobs.single().waiting!!, "Nomad")
        store.setPaused(id, true)
        assertContains(Waiting.report(config.status, store, { null }).jobs.single().waiting!!, "paused")
    }
    @Test fun `maintenance admission excludes ordinary jobs and resumes capture analysis`() {
        val config = Config(dataDir = Files.createTempDirectory("analysis"))
        val store = Store(config.database)
        store.create("solve", "main", emptyList(), experiment = ExperimentSpec("solve"))
        assertNull(store.nextAnalysis())
        val spec = ExperimentSpec("analysis", analyze = AnalyzeSpec(1))
        val id = store.create(spec.name, "main", emptyList(), experiment = spec)
        assertEquals(id, store.nextAnalysis()!!.id)
        assertTrue(store.localBusy())
        assertEquals(id, store.nextAnalysis()!!.id)
    }
}
