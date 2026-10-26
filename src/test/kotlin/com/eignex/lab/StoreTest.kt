package com.eignex.lab

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StoreTest {
    private fun store() = Store(Files.createTempDirectory("lab").resolve("lab.db"))

    @Test
    fun `each case says where it ran, one run before the lab recorded that reading as its job's host`() {
        val store = store()
        fun job(host: String): Long {
            val spec = ExperimentSpec("e", listOf(mapOf("suite" to "s")), host = host)
            val id = store.create("e", "main", emptyList(), experiment = spec)
            store.plan(id, listOf(PlannedArm(Arm("a", emptyMap()), "sha")), listOf(Problem("s", "p")), Experiments.cases(1, 1, listOf(1, 2, 3)),
                List(3) { "true" to 1L })
            return id
        }
        val local = job(Experiments.LAB_HOST)
        val cloud = job(Experiments.AWS_HOST)
        for (id in listOf(local, cloud)) {
            store.commandStarted(id, 0)
            store.commandFinished(id, 0, 0)
            store.commandStarted(id, 1, "i-7", "echo rendered")
        }

        assertEquals(listOf("local", "i-7", null), store.cases(local).map { it.host })
        assertEquals(listOf("cloud", "i-7", null), store.cases(cloud).map { it.host })
        assertEquals(listOf(null, "i-7", null), store.job(local)!!.commands.map { it.host })
        assertEquals("echo rendered", store.job(local)!!.commands[1].cmd)
    }

    @Test
    fun `each case carries its native stdout files with their hashes, read from the job directory`() {
        val store = store()
        val dir = Files.createTempDirectory("job")
        val id = store.create("e", "main", emptyList(), experiment = ExperimentSpec("e", listOf(mapOf("suite" to "s"))))
        store.plan(id, listOf(PlannedArm(Arm("a", emptyMap()), "sha")), listOf(Problem("s", "p")), Experiments.cases(1, 1, listOf(1, 2)),
            List(2) { "true" to 1L })
        dir.resolve("cases/0/profile-x").toFile().mkdirs()
        dir.resolve("cases/0/p_1.out").toFile().writeText("sat\n")
        dir.resolve("cases/0/p_1.json").toFile().writeText("{}")
        dir.resolve("cases/0/profile-x/cli.out").toFile().writeText("not the solver's")

        store.caseStdout(id, 0, CaseFile.stdout(dir, 0))
        store.caseStdout(id, 1, CaseFile.stdout(dir, 1))

        val expected = CaseFile("cases/0/p_1.out", 4, "d9dbf94b05b8314af9ca7f2034e92685c33447917605a7243b1099c693a12e5e")
        assertEquals(listOf(listOf(expected), emptyList()), store.cases(id).map { it.stdout })
        assertTrue("\"sha256\":\"${expected.sha256}\"" in
            io.ktor.serialization.kotlinx.json.DefaultJson.encodeToString(CaseResult.serializer(), store.cases(id)[0]))
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `each case records whether the lab ran its CLI with debug on`() {
        val store = store()
        val spec = ExperimentSpec("e", listOf(mapOf("suite" to "s")), grid = mapOf("debug" to listOf("true", "false")))
        val id = store.create("e", "main", emptyList(), experiment = spec)
        val arms = Experiments.arms(spec) + Arm("default", emptyMap())
        store.plan(id, arms.map { PlannedArm(it, "sha") }, listOf(Problem("s", "p")), Experiments.cases(1, 3, emptyList()),
            List(3) { "true" to 1L })

        assertEquals(mapOf("debug=true" to true, "debug=false" to false, "default" to true), store.cases(id).associate { it.arm to it.labDebug })
        assertTrue("\"labDebug\":false" in io.ktor.serialization.kotlinx.json.DefaultJson.encodeToString(CaseResult.serializer(), store.cases(id).single { it.arm == "debug=false" }))
    }

    @Test
    fun `a cancel from a second process lands while the other one keeps writing`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        val runner = Store(file)
        val api = Store(file)
        val id = runner.create("a", "main", List(200) { "true" to 10L })
        runner.startForTest()
        val writer = thread {
            repeat(200) { index ->
                runner.commandStarted(id, index)
                runner.commandFinished(id, index, 0)
            }
        }

        val cancelled = api.requestCancel(id)
        writer.join()

        assertTrue(cancelled == CancelOutcome.REQUESTED && runner.cancelRequested(id))
    }

    @Test
    fun `a job left running by a crashed runner is resumed before a queued one`() {
        val store = store()
        val crashed = store.create("a", "main", listOf("true" to 10L))
        store.create("b", "main", listOf("true" to 10L))
        store.startForTest()

        assertEquals(crashed, store.startForTest()?.id)
    }

    @Test
    fun `cancelling a queued job cancels it without a runner`() {
        val store = store()
        val id = store.create("a", "main", listOf("true" to 10L))

        store.requestCancel(id)

        assertEquals(Status.CANCELLED, store.job(id)!!.status)
    }

    @Test
    fun `a queued job left with its cancel requested is found for the runner to end`() {
        val file = Files.createTempDirectory("lab").resolve("lab.db")
        val store = Store(file)
        val stuck = store.create("a", "main", listOf("true" to 10L))
        val waiting = store.create("b", "main", listOf("true" to 10L))
        // What a runner from before left: requeued after its cancel was requested.
        DriverManager.getConnection("jdbc:sqlite:$file").use { it.createStatement().executeUpdate("UPDATE jobs SET cancel_requested = 1 WHERE id = $stuck") }

        assertEquals(listOf(stuck), store.queuedCancels())
        assertEquals(Status.CANCELLED, store.requeue(stuck))
        assertEquals(listOf(Status.CANCELLED, Status.QUEUED), listOf(stuck, waiting).map { store.job(it)!!.status })
        assertEquals(emptyList(), store.queuedCancels())
    }

    @Test
    fun `cancelling a finished job reports it as finished`() {
        val store = store()
        val id = store.create("a", "main", listOf("true" to 10L))
        store.startForTest()
        store.finish(id, Status.DONE)

        assertEquals(CancelOutcome.FINISHED, store.requestCancel(id))
    }

    @Test
    fun `a job's clock stops while it waits in the queue`() {
        val store = store()
        val id = store.create("a", "main", listOf("true" to 10L))
        store.startForTest()
        store.requeue(id)

        val waiting = checkNotNull(store.job(id))

        assertEquals(null to waiting.runMs, waiting.runningSince to waiting.elapsedMs(now() + 3_600_000))
    }

    @Test
    fun `a launch asked for lasts until its run is queued and keeps the schedule's check time`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val id = store.createSchedule("sweep", "main", ExperimentSpec("sweep", listOf(mapOf("suite" to "s")), description = "d"), 3600)
        store.scheduleChecked(id, 1_000)

        val launched = store.launchNow(id)
        val asked = store.schedules().single()
        store.scheduleRan(id, "abc", 7)
        val ran = store.schedules().single()

        assertEquals(listOf(true, true, 1_000L), listOf(launched, asked.launchRequested, asked.checkedAt))
        assertEquals(false, ran.launchRequested)
    }

    private fun autoJob(store: Store, name: String, priority: Int = 0, host: String = Experiments.AUTO_HOST) =
        store.create(name, "main", emptyList(), priority = priority,
            experiment = ExperimentSpec(name, listOf(mapOf("suite" to "s")), host = host))


}
