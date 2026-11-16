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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ResourceObservationTest {
    @Test
    fun `CPU identity ignores changing frequency and flag order`() {
        val first = "vendor_id: vendor\ncpu family: 6\nmodel: 1\nmodel name: processor\nstepping: 2\nflags: sse avx\ncpu MHz: 2000"
        val next = first.replace("sse avx", "avx sse").replace("2000", "3000")

        assertNotNull(ResourceObservation.cpuIdentity(first))
        assertEquals(ResourceObservation.cpuIdentity(first), ResourceObservation.cpuIdentity(next))
        assertNotEquals(ResourceObservation.cpuIdentity(first), ResourceObservation.cpuIdentity(first.replace("model: 1", "model: 2")))
    }

    @Test
    fun `incomplete CPU identity remains unavailable`() {
        assertNull(ResourceObservation.cpuIdentity("model name: processor\nflags: avx"))
    }
}
