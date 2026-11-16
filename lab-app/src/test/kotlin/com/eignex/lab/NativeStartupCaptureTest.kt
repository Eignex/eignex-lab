package com.eignex.lab

import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.util.ProcessSupervisor
import com.eignex.lab.worker.NativeStartupCapture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class NativeStartupCaptureTest {
    @ParameterizedTest
    @ValueSource(strings = ["--config-dirs", "--version"])
    fun `only exact identity queries under the owned worktree match`(option: String) {
        assertTrue(NativeStartupCapture.matches("/owned/runtime/bin/minizinc", listOf(option), Path.of("/owned")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["/unowned/minizinc", "/owned-other/minizinc", "/owned/../other/minizinc", "/owned/bash"])
    fun `other executable locations cannot become sample targets`(executable: String) {
        assertFalse(NativeStartupCapture.matches(executable, listOf("--version"), Path.of("/owned")))
    }

    @Test fun `compile commands and additional arguments cannot become sample targets`() {
        assertFalse(NativeStartupCapture.matches("/owned/minizinc", listOf("--compile", "model.mzn"), Path.of("/owned")))
        assertFalse(NativeStartupCapture.matches("/owned/minizinc", listOf("--version", "extra"), Path.of("/owned")))
    }

    @Test fun `unsupported workers retain explicit unavailability without wrapping the command`() {
        val root = Files.createTempDirectory("native-startup")
        val command = listOf("bash", "-c", "exit 7")
        val output = root.resolve("output")
        NativeStartupCapture(command, root, output, root,
            NativeStartupCapture.Binding("session", 2, 3, "worker", "source"),
            ProcessSupervisor(root.resolve("absent.sock")), { 1000 }, false).use {
            assertEquals(command, it.argv)
        }
        val receipt = Json.parseToJsonElement(Files.readString(output.resolve("status.json"))).jsonObject
        assertEquals("unsupported-platform", receipt.getValue("outcome").jsonPrimitive.content)
        assertEquals("native-startup-sample-v1", receipt.getValue("policy").jsonPrimitive.content)
        assertEquals("null", receipt.getValue("cpuBeforeNs").toString())
    }

    @Test fun `an absent sampler is explicit and does not start a supervisor request`() {
        val root = Files.createTempDirectory("native-startup")
        val output = root.resolve("output")
        NativeStartupCapture(listOf("true"), root, output, root,
            NativeStartupCapture.Binding("session", 0, 1, "worker", "source"),
            ProcessSupervisor(root.resolve("absent.sock")), { 1000 }, true, root.resolve("missing-sample")).close()
        val receipt = Json.decodeFromString<NativeStartupCapture.Receipt>(Files.readString(output.resolve("status.json")))
        assertEquals("sample-tool-unavailable", receipt.outcome)
        assertNull(receipt.target)
    }

    @Test fun `native diagnostic jobs are instrumented in both stored case views`() {
        val spec = Json.decodeFromJsonElement<ExperimentSpec>(experimentInput(Json.parseToJsonElement(
            """{"name":"capture","bench":{"manifest":"bench.json"},"measurement":"native-startup"}""").jsonObject))
        assertTrue(spec.instrumented)
        assertFalse(spec.measuresCli)
        assertEquals(1, spec.vcpus)
        Experiments.validate(spec, 4)
        assertNotEquals(Experiments.fingerprint(spec.copy(measurement = Measurement.NONE)), Experiments.fingerprint(spec))
        val store = Store(Files.createTempDirectory("native-startup-store").resolve("lab.db"))
        val id = store.create(spec.name, "main", emptyList(), experiment = spec)
        store.plan(id, Experiments.arms(spec).map { PlannedArm(it, "sha") }, listOf(Problem("bench", "p")),
            Experiments.cases(1, 1, emptyList()), listOf("true" to 1L))
        assertTrue(store.plans.cases(id).single().instrumented)
        assertTrue(store.plans.scoringCases(id).single().instrumented)
    }

    @Test fun `sampler arguments retain literal paths and impose their own file ceiling`() {
        val command = NativeStartupCapture.sampleCommand(Path.of("/usr/bin/sample"), 123, Path.of("/output with spaces/sample.txt"))
        assertEquals("123", command[5])
        assertEquals("/output with spaces/sample.txt", command[6])
        assertTrue(command[2].startsWith("ulimit -f 1024 || exit 125;"))
        assertFailsWith<IllegalArgumentException> { NativeStartupCapture.sampleCommand(Path.of("sample"), 0, Path.of("out")) }
    }

    @Test fun `PID reuse and changed query identity invalidate a target`() {
        val target = NativeStartupCapture.Target(1, "root-start", 2, "child-start", "/owned/minizinc", "--version", "inode", 10, "mtime")
        assertTrue(NativeStartupCapture.sameProcess(target, "child-start", "/owned/minizinc", listOf("--version")))
        assertFalse(NativeStartupCapture.sameProcess(target, "reused-pid", "/owned/minizinc", listOf("--version")))
        assertFalse(NativeStartupCapture.sameProcess(target, "child-start", "/other/minizinc", listOf("--version")))
        assertFalse(NativeStartupCapture.sameProcess(target, "child-start", "/owned/minizinc", listOf("--config-dirs")))
    }

    @Test fun `diagnostic setup spends the original case allowance without rounding it up`() {
        assertEquals(7L, NativeStartupCapture.remainingCaseSeconds(10_000_000_000, 2_500_000_000))
        assertEquals(0L, NativeStartupCapture.remainingCaseSeconds(10_000_000_000, 9_500_000_000))
        assertEquals(0L, NativeStartupCapture.remainingCaseSeconds(10_000_000_000, 11_000_000_000))
    }
}
