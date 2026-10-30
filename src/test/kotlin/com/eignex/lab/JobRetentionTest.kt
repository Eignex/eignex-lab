package com.eignex.lab

import com.eignex.lab.execution.*
import com.eignex.lab.util.*

import kotlin.test.Test
import kotlin.test.assertEquals

class JobRetentionTest {
    @Test
    fun `jobs ended past retention are forgotten unless held or refused`() {
        val now = 30 * MS_PER_DAY
        val forgotten = mutableListOf<Long>()
        val retention = JobRetention(
            ended = { listOf(1L to 0L, 2L to 0L, 3L to 0L, 4L to now - MS_PER_DAY) },
            maxAgeMs = 7 * MS_PER_DAY,
            held = { setOf(2L) },
            forget = { id -> (id != 3L).also { if (it) forgotten += id } },
            clock = { now },
            report = {},
        )

        assertEquals(1, retention.run())
        assertEquals(listOf(1L), forgotten)
    }
}
