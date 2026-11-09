package com.eignex.lab

import com.eignex.lab.domain.Auth
import com.eignex.lab.domain.Config
import com.eignex.lab.domain.HostReport
import com.eignex.lab.util.VolumeAccess
import com.eignex.lab.store.Store
import com.eignex.lab.web.ApiServices
import com.eignex.lab.web.PageQueries
import com.eignex.lab.web.PageStorage
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageStorageTest {
    @Test fun `queue queries return while an external volume probe is blocked`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val root = Files.createTempDirectory("page-storage")
        val jobs = Files.createDirectory(root.resolve("jobs"))
        val config = Config(dataDir = root, jobsDir = jobs, corpusDir = root, minFreeBytes = 0, auth = Auth.Disabled,
            volumeAccess = VolumeAccess(timeoutMs = 60_000) { _, dir ->
                if (dir == jobs) {
                    entered.countDown()
                    release.await()
                }
                null
            })
        val executor = Executors.newSingleThreadExecutor()
        try {
            Store(config.database).let { store ->
                ApiServices(config, store, HostReport("test", 4, "java")).use { services ->
                    services.pageStorage.start()
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    val query = executor.submit<Boolean> {
                        val view = services.pageQueries.index(null, null)
                        assertTrue(view.active.isEmpty())
                        assertEquals("storage status is being checked", view.held)
                        true
                    }
                    // The probe has a 60-second deadline and is still held: this request must not join it.
                    assertTrue(query.get(5, TimeUnit.SECONDS))
                    assertEquals(1L, release.count)
                }
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun `readers retain the last completed snapshot during a slow refresh`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var reads = 0
        val healthy = PageStorage.Snapshot(1234, null)
        PageStorage(read = {
            if (++reads > 1) {
                entered.countDown()
                release.await()
            }
            healthy
        }, everyMs = 1).use { cache ->
            try {
                cache.start()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                repeat(100) { assertEquals(healthy, cache.latest) }
                assertNull(cache.latest.held)
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun `a stalled refresh cannot keep displaying old healthy storage indefinitely`() {
        val clock = AtomicLong(0)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var reads = 0
        PageStorage(read = {
            if (++reads > 1) {
                entered.countDown()
                release.await()
            }
            PageStorage.Snapshot(1234, null)
        }, everyMs = 1, maxAgeMs = 30, clock = clock::get).use { cache ->
            try {
                cache.start()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                clock.set(31)
                assertNull(cache.latest.free)
                assertEquals("storage status is stale; waiting for storage", cache.latest.held)
            } finally {
                release.countDown()
            }
        }
    }
}
