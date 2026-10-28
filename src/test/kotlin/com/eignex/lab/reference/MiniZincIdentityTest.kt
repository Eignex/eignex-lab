package com.eignex.lab.reference

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class MiniZincIdentityTest {
    @Test
    fun `a builtin reference retains compiler and selected solver versions`() {
        val configuration = Json.parseToJsonElement("""{"id":"org.gecode.gecode","version":"6.4.0"}""").jsonObject

        val stats = MiniZincIdentity.describe("MiniZinc 2.10.1", "compiler-hash", configuration)

        assertEquals("org.gecode.gecode", stats["referenceSolverId"])
        assertEquals("6.4.0", stats["referenceSolverVersion"])
        assertEquals("compiler-hash", stats["referenceSolverSha256"])
        assertContains(stats.getValue("referenceVersion"), "solver=org.gecode.gecode|version=6.4.0")
    }

    @Test
    fun `missing reference versions cannot qualify a selected solver`() {
        for (version in listOf("", "unknown")) {
            val configuration = Json.parseToJsonElement("""{"id":"org.gecode.gecode","version":"$version"}""").jsonObject

            assertFailsWith<IllegalArgumentException> { MiniZincIdentity.describe("MiniZinc", "hash", configuration) }
        }
    }

    @Test
    fun `selected solver configuration changes invalidate its reference identity`() {
        val original = Json.parseToJsonElement("""{"id":"org.gecode.gecode","version":"6.4.0","extraInfo":{"defaultFlags":[]}}""").jsonObject
        val changed = Json.parseToJsonElement("""{"id":"org.gecode.gecode","version":"6.4.0","extraInfo":{"defaultFlags":["-f"]}}""").jsonObject

        assertNotEquals(MiniZincIdentity.describe("MiniZinc", "hash", original)["referenceVersion"],
            MiniZincIdentity.describe("MiniZinc", "hash", changed)["referenceVersion"])
    }
}
