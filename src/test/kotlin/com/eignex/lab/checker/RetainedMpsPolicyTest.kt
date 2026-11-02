package com.eignex.lab.checker

import com.eignex.lab.domain.Problem
import com.eignex.lab.reference.sha256
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class RetainedMpsPolicyTest {
    @TempDir lateinit var dir: File

    @Test fun `retained Bell3a and Egout points keep strict rejections and receive separate conditional policy checks`() {
        for ((id, name) in listOf("10051-0" to "bell3a", "10028-4" to "egout", "10028-7" to "egout", "10028-8" to "egout")) {
            val fixture = Json.parseToJsonElement(checkNotNull(javaClass.getResource(
                "/checker/mps/retained-policy/$id.json")).readText()).jsonObject
            val original = fixture.getValue("record").jsonObject
            val source = checkNotNull(javaClass.getResource("/checker/mps/retained-policy/$name.mps")).readBytes()
            assertEquals(original.getValue("sourceHashes").jsonObject.getValue("model").jsonPrimitive.content, sha256(source))
            assertEquals(fixture.getValue("origin").jsonObject.getValue("witnessSha256").jsonPrimitive.content,
                sha256(original.getValue("finalWitness").jsonPrimitive.content.toByteArray()))
            val model = File(dir, "$name.mps").apply { writeBytes(source) }
            val dispatcher = SourceChecker(listOf(RationalMpsChecker()), SourceLocator(dir), File(dir, "scratch"))
            val problem = Problem("retained", name, format = "MPS", model = model.path)
            val unqualified = dispatcher.check(problem, original)!!
            assertEquals(LabCheck.UNKNOWN, unqualified.state)
            assertNull(unqualified.mpsChecks!!.semanticsSha256)
            assertEquals(LabCheck.INVALID, unqualified.mpsChecks!!.exact.state)
            val conditional = JsonObject(original + ("numericalSemantics" to fixture.getValue("supplementalSemantics")))
            val checked = dispatcher.check(problem, conditional)!!
            val checks = checked.mpsChecks!!
            assertEquals(LabCheck.INVALID, checks.exact.state)
            assertEquals(fixture.getValue("retainedLabCheck").jsonObject.getValue("reason").jsonPrimitive.content,
                checks.exact.reason)
            assertTrue(checks.configured.state in setOf(LabCheck.VALID, LabCheck.INVALID), checked.reason)
            assertNotNull(checks.comparisonIdentity)
            println("retained-policy-$id ${checked.json()}")
            assertEquals(checked, LabCheck.parse(checked.json()))
            val wrongObjective = dispatcher.check(problem, JsonObject(conditional + ("exactObjective" to JsonPrimitive("-999999999"))))!!
            assertEquals(LabCheck.INVALID, wrongObjective.state)
            val badWitness = original.getValue("finalWitness").jsonPrimitive.content
                .replaceFirst(Regex("=[^ ]+"), "=999999999999999")
            val wrongPoint = dispatcher.check(problem, JsonObject(conditional + ("finalWitness" to JsonPrimitive(badWitness))))!!
            assertEquals(LabCheck.INVALID, wrongPoint.state)
        }
    }
}
