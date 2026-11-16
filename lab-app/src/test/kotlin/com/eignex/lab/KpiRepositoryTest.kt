package com.eignex.lab

import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KpiRepositoryTest {
    @Test fun `ended jobs count by day and failures group by cause`() {
        val store = Store(Files.createTempDirectory("kpi").resolve("lab.db"))
        val done = store.create("done", "main", emptyList(), experiment = ExperimentSpec("done"))
        val failed = store.create("failed", "main", emptyList(), experiment = ExperimentSpec("failed"))
        val outage = store.create("outage", "main", emptyList(), experiment = ExperimentSpec("outage"))
        store.create("queued", "main", emptyList(), experiment = ExperimentSpec("queued"))
        store.finish(done, Status.DONE)
        store.finish(failed, Status.FAILED, "source-build: exit 1\nFAILURE: Build failed with an exception.")
        store.finish(outage, Status.FAILED, "source-build: exit 1\n> Received status code 504 from server")
        val report = store.kpis.report(7)
        val day = report.days.single()
        assertEquals(1, day.jobsDone)
        assertEquals(2, day.jobsFailed)
        assertEquals(mapOf("source-build" to 2), day.failures)
        assertEquals(0, day.casesCompleted)
        assertNull(day.preparationMs)
        assertEquals(0, day.preparationAttempts)
    }

    @Test fun `failure causes drop commits and parenthesized detail`() {
        assertEquals("Nomad preparation stopped after 3 worker exits without acceptance at stage compilation",
            KpiRepository.failureCause("Nomad preparation stopped after 3 worker exits without acceptance at stage compilation (exit 70, release fdcb3a79e34130ca0841789e455f86179ea76582): boom"))
        assertEquals("unknown", KpiRepository.failureCause(null))
        assertEquals("cancelled by user", KpiRepository.failureCause("cancelled by user"))
    }

    @Test fun `spread takes the median and the 90th percentile`() {
        assertNull(Spread.of(emptyList()))
        assertEquals(Spread(10, 5, 9), Spread.of((1L..10L).toList().shuffled()))
        assertEquals(Spread(1, 7, 7), Spread.of(listOf(7L)))
    }
}
