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

import kotlin.test.*

class CoordinatorSignalTest {
    @Test fun `wakeups coalesce without being lost before the consumer waits`() {
        val signal = CoordinatorSignal()
        repeat(1000) { signal.wake() }
        assertTrue(signal.await(0)); assertFalse(signal.await(0))
        signal.wake(); assertTrue(signal.await(0)); assertFalse(signal.await(0))
    }

    @Test fun `extra listener requires explicit private network authorization`() {
        val settings = NomadCoordinatorSettings("https://nomad", "token", "keys", "trust", "password")
        assertFailsWith<IllegalArgumentException> { settings.copy(privateBindAddress = "10.44.0.3") }
        assertEquals("10.44.0.3", settings.copy(privateNetwork = true, privateBindAddress = "10.44.0.3").privateBindAddress)
        assertFailsWith<IllegalArgumentException> { settings.copy(privateNetwork = true, privateBindAddress = settings.bindAddress) }
    }
}
