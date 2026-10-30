package com.eignex.lab

import com.eignex.lab.capacity.ResourceBudget
import com.eignex.lab.util.hashFile
import com.eignex.lab.worker.AdapterResources
import com.eignex.lab.worker.ComparisonExecution
import com.eignex.lab.worker.ResourceObservation
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.*

class AdapterResourcesTest {
    @TempDir
    lateinit var work: Path

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `external timing includes preflight and the child invocation without swallowing its failure`() {
        Files.createDirectories(work.resolve("cases/0"))
        val invocation = "printf 'preflight\\n'; bash -c 'printf \"child\\n\"; exit 7'"
        val builder = ProcessBuilder("bash", "-c", AdapterResources.command(invocation, 0)).redirectErrorStream(true)
        builder.environment()["JOB_DIR"] = work.toString()
        val process = builder.start()
        try {
            assertTrue(process.waitFor(250, TimeUnit.MILLISECONDS))
            assertEquals(7, process.exitValue())
            assertEquals("preflight\nchild\n", process.inputStream.bufferedReader().readText())
            assertEquals(7, AdapterResources.values(Files.readString(work.resolve("cases/0/${AdapterResources.RAW}"))).exit)
        } finally {
            if (process.isAlive) {
                process.toHandle().descendants().use { children -> children.toList().asReversed().forEach { it.destroyForcibly() } }
                process.destroyForcibly()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["1.25", "1,25"])
    fun `timer CPU decimals retain their exact printed precision`(user: String) {
        val text = "userSeconds=$user\nsystemSeconds=0.05\nwallSeconds=1.40\nmaxProcessRssKiB=42\nexit=0\n"

        val values = AdapterResources.values(text)

        assertEquals("1.25", values.userSeconds)
        assertEquals("0.05", values.systemSeconds)
        assertEquals("1.40", values.wallSeconds)
        assertEquals(42L, values.maxProcessRssKiB)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "duplicate", "negative", "nan", "oversize", "extra"])
    fun `incomplete or malformed timer values cannot supply CPU evidence`(shape: String) {
        val original = "userSeconds=1.25\nsystemSeconds=0.05\nwallSeconds=1.40\nmaxProcessRssKiB=42\nexit=0\n"
        val text = when (shape) {
            "missing" -> original.replace("systemSeconds=0.05\n", "")
            "duplicate" -> original.replace("systemSeconds", "userSeconds")
            "negative" -> original.replace("1.25", "-1.25")
            "nan" -> original.replace("1.25", "NaN")
            "oversize" -> original.replace("1.25", "9".repeat(4097))
            else -> original + "unknown=1\n"
        }

        assertFailsWith<IllegalArgumentException> { AdapterResources.values(text) }
    }

    @Test
    fun `an accepted measurement binds the external command and both artifact hashes`() {
        val directory = Files.createDirectories(work.resolve("cases/0"))
        val raw = "userSeconds=1.25\nsystemSeconds=0.05\nwallSeconds=1.40\nmaxProcessRssKiB=42\nexit=0\n"
        Files.writeString(directory.resolve(AdapterResources.RAW), raw)
        val command = "exec adapter 'literal value'"
        AdapterResources.capture(work, 0, command, 0, "a".repeat(64))
        val file = directory.resolve(AdapterResources.RECEIPT)
        val observation = ResourceObservation("cgroup-v2-hard", ResourceBudget(1000, 4608, 1), "100000 100000", "4831838208",
            "25", "Linux", "amd64", emptyMap(), "b".repeat(64), 1, 2)
        val physical = ComparisonExecution.record("""{"solver":"klause"}""", observation, "c".repeat(64), "release")

        val projected = AdapterResources.project(physical, true, Files.readString(file), hashFile(file),
            raw, hashFile(directory.resolve(AdapterResources.RAW)), command)
        val record = Json.parseToJsonElement(projected!!).jsonObject

        assertEquals("complete", record.getValue("labAdapterResources").jsonObject.getValue("state").jsonPrimitive.content)
        assertNotNull(ComparisonExecution.frame(record))
        assertNotEquals(ComparisonExecution.series(Json.parseToJsonElement(physical!!).jsonObject), ComparisonExecution.series(record))
    }

    @ParameterizedTest
    @ValueSource(strings = ["command", "receipt-hash", "raw-hash", "raw-content"])
    fun `an unbound measurement remains unavailable`(changed: String) {
        val directory = Files.createDirectories(work.resolve("cases/0"))
        val raw = "userSeconds=1.25\nsystemSeconds=0.05\nwallSeconds=1.40\nmaxProcessRssKiB=42\nexit=0\n"
        Files.writeString(directory.resolve(AdapterResources.RAW), raw)
        AdapterResources.capture(work, 0, "adapter", 0, "a".repeat(64))
        val file = directory.resolve(AdapterResources.RECEIPT)

        val projected = AdapterResources.project("{}", true, Files.readString(file),
            if (changed == "receipt-hash") "b".repeat(64) else hashFile(file),
            if (changed == "raw-content") raw.replace("1.25", "1.26") else raw,
            if (changed == "raw-hash") "b".repeat(64) else hashFile(directory.resolve(AdapterResources.RAW)),
            if (changed == "command") "other" else "adapter")

        assertEquals("unavailable", Json.parseToJsonElement(projected!!).jsonObject
            .getValue("labAdapterResources").jsonObject.getValue("state").jsonPrimitive.content)
    }

    @Test
    fun `interruption retains partial timer values without qualifying the measurement`() {
        val directory = Files.createDirectories(work.resolve("cases/0"))
        Files.writeString(directory.resolve(AdapterResources.RAW),
            "userSeconds=1.25\nsystemSeconds=0.05\nwallSeconds=1.40\nmaxProcessRssKiB=42\nexit=0\n")
        Files.writeString(work.resolve("0.termination"), "interrupted")

        AdapterResources.capture(work, 0, "adapter", 124, "a".repeat(64))
        val receipt = Json.parseToJsonElement(Files.readString(directory.resolve(AdapterResources.RECEIPT))).jsonObject

        assertEquals("unavailable", receipt.getValue("state").jsonPrimitive.content)
        assertEquals("1.25", receipt.getValue("resources").jsonObject.getValue("userSeconds").jsonPrimitive.content)
        assertTrue(receipt.getValue("interrupted").jsonPrimitive.boolean)
    }

    @Test
    fun `a missing timer file cannot become a zero CPU measurement`() {
        AdapterResources.capture(work, 0, "adapter", 0, "a".repeat(64))

        val receipt = Json.parseToJsonElement(Files.readString(work.resolve("cases/0/${AdapterResources.RECEIPT}"))).jsonObject

        assertEquals("unavailable", receipt.getValue("state").jsonPrimitive.content)
        assertEquals(JsonNull, receipt["resources"])
    }

    @Test
    fun `a failed adapter retains its completed CPU measurement`() {
        val directory = Files.createDirectories(work.resolve("cases/0"))
        Files.writeString(directory.resolve(AdapterResources.RAW),
            "userSeconds=1.25\nsystemSeconds=0.05\nwallSeconds=1.40\nmaxProcessRssKiB=42\nexit=7\n")

        AdapterResources.capture(work, 0, "adapter", 7, "a".repeat(64))
        val receipt = Json.parseToJsonElement(Files.readString(directory.resolve(AdapterResources.RECEIPT))).jsonObject

        assertEquals("complete", receipt.getValue("state").jsonPrimitive.content)
        assertEquals(7, receipt.getValue("processExit").jsonPrimitive.int)
        assertEquals("1.25", receipt.getValue("resources").jsonObject.getValue("userSeconds").jsonPrimitive.content)
    }

    @Test
    fun `a requested missing measurement withholds an otherwise qualified physical frame`() {
        val observation = ResourceObservation("cgroup-v2-hard", ResourceBudget(1000, 4608, 1), "100000 100000", "4831838208",
            "25", "Linux", "amd64", emptyMap(), "b".repeat(64), 1, 2)
        val physical = ComparisonExecution.record("""{"solver":"klause"}""", observation, "c".repeat(64), "release")

        val projected = AdapterResources.project(physical, true, null, null, null, null, "adapter")

        assertNull(ComparisonExecution.frame(Json.parseToJsonElement(projected!!).jsonObject))
    }
}
