package com.eignex.lab

import com.eignex.lab.domain.*
import com.eignex.lab.nomad.*
import kotlinx.serialization.json.*
import kotlin.test.*

class PreparationAdmissionTest {
    private val template = NomadBatchJob("preparation", NomadPlatform.LINUX, "general", NomadCpuCapacity(32000, 16),
        ResourceBudget(2000, 4096), listOf("worker"), "registry/worker@sha256:" + "d".repeat(64), excludedNodes = setOf("operator-excluded"))

    @Test fun `full preparation nodes are excluded and become eligible when a reservation ends`() {
        val original = PreparationAdmission.mark(template.render(), 2)
        val held = listOf(PreparationAdmission.Reservation("busy", "general", false), PreparationAdmission.Reservation("busy", "general", false),
            PreparationAdmission.Reservation("available", "general", false))
        val placed = PreparationAdmission.place(original, held)
        assertEquals(setOf("busy", "operator-excluded"), (SessionPlacement.of(placed).pool as WorkerPool.Cloud).excludedNodes)
        assertEquals(2, SessionPlacement.of(placed).preparationSlots)
        assertEquals(placed, PreparationAdmission.place(placed, held))
        assertEquals(original, PreparationAdmission.place(placed, held.drop(1)))
        assertEquals(original, PreparationAdmission.clear(placed))
    }

    @Test fun `one unbound preparation per cloud class prevents placement races`() {
        val held = listOf(PreparationAdmission.Reservation(null, "general", false))
        assertFalse(PreparationAdmission.allows("general", false, 2, held))
        assertTrue(PreparationAdmission.allows("other", false, 2, held))
        assertTrue(PreparationAdmission.allows("general", false, 2, held.map { it.copy(node = "bound") }))
    }

    @Test fun `native admission keeps its compiler limit and solve placement is untouched`() {
        val held = List(2) { PreparationAdmission.Reservation("native", "mac", true) }
        assertFalse(PreparationAdmission.allows("mac", true, 2, held))
        assertTrue(PreparationAdmission.allows("mac", true, 2, held.drop(1)))
        val solve = template.render()
        assertEquals(solve, PreparationAdmission.place(solve, held))
        assertFailsWith<IllegalArgumentException> { PreparationAdmission.mark(solve, 0) }
    }
}
