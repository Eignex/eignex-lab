package com.eignex.lab

import com.eignex.lab.web.Html
import com.eignex.lab.web.QueuePageCache
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueuePageCacheTest {
    @Test fun `requests return without joining the first database and estimate refresh`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicLong()
        val executor = Executors.newSingleThreadExecutor()
        QueuePageCache(read = {
            reads.incrementAndGet()
            entered.countDown()
            release.await()
            Html("ready<!--queue-snapshot-->")
        }, loading = { Html("loading<!--queue-snapshot-->") }).use { cache ->
            try {
                // A cold request registers the page, but must not perform its reads itself.
                assertTrue(cache.get(null, null).markup.startsWith("loading"))
                assertEquals(0L, reads.get())
                cache.start()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val responses = executor.submit<Boolean> {
                    repeat(100) { assertTrue(cache.get(null, null).markup.startsWith("loading")) }
                    assertTrue(cache.get(123, "different history").markup.startsWith("loading"))
                    true
                }
                assertTrue(responses.get(5, TimeUnit.SECONDS))
                assertEquals(1L, reads.get())
                assertEquals(1L, release.count)
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test fun `slow background refresh retains data and makes its age explicit`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clock = AtomicLong()
        var reads = 0
        QueuePageCache(read = {
            if (++reads > 1) {
                entered.countDown()
                release.await()
            }
            Html("finished queue<!--queue-snapshot-->")
        }, loading = { Html("loading") }, everyMs = 0, maxAgeMs = 30, clock = clock::get).use { cache ->
            try {
                cache.start()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertFalse(cache.get(null, null).markup.contains("stale"))
                clock.set(31)
                val page = cache.get(null, null).markup
                assertTrue(page.startsWith("finished queue"))
                assertTrue(page.contains("snapshot is stale"))
                assertEquals(1L, release.count)
            } finally {
                release.countDown()
            }
        }
    }
}
