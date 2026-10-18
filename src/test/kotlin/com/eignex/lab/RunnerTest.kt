package com.eignex.lab

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class RunnerTest {
    @Test
    fun `a whole run timeout fails the profile and preserves its output after cleanup`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.dataDir.resolve("lab.db"))
        val spec = ExperimentSpec("bounded", profile = ProfileMain("com.eignex.klause.bench.profile.Tool", runTimeoutSec = 2))
        val id = store.create("bounded", "main", listOf("echo captured; sleep 30" to 60L), experiment = spec)
        config.worktree(id).createDirectories()
        store.next()
        store.setup(id, "head")

        Runner(config, store).workWithDeadline(store.job(id)!!)

        val job = store.job(id)!!
        assertEquals(Status.FAILED, job.status)
        assertTrue(job.error!!.contains("whole-run deadline"))
        assertTrue(config.jobDir(id).resolve("0.out").toFile().readText().contains("captured"))
        assertFalse(config.worktree(id).exists())
        // The case thread records the kill once it sees its process gone, which can trail the job's end a little.
        assertTrue(eventually { Termination.read(config.jobDir(id), 0).any { "deadline:" in it } })
    }

    @Test
    fun `a bounded profile is done after its worktree cleanup succeeds`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.dataDir.resolve("lab.db"))
        val spec = ExperimentSpec("bounded", profile = ProfileMain("com.eignex.klause.bench.profile.Tool", runTimeoutSec = 5))
        val id = store.create("bounded", "main", listOf("true" to 60L), experiment = spec)
        config.worktree(id).createDirectories()
        store.next()
        store.setup(id, "head")

        Runner(config, store).workWithDeadline(store.job(id)!!)

        assertEquals(Status.DONE, store.job(id)!!.status)
        assertFalse(config.worktree(id).exists())
    }

    @Test
    fun `deleting a worktree removes a shared link without touching what it points at`() {
        val root = Files.createTempDirectory("lab")
        val shared = root.resolve("shared").createDirectories()
        shared.resolve("result.json").writeText("{}")
        val worktree = root.resolve("work").resolve("build").createDirectories().parent
        Files.createSymbolicLink(worktree.resolve("build").resolve("bench-cache"), shared)

        deleteTree(worktree)

        assertTrue(!worktree.exists() && shared.resolve("result.json").exists())
    }

    @Test
    fun `killing the group reaches a child whose parent shell already died`() {
        val process = ProcessBuilder(inSession("bash", "-c", "sleep 30 & echo \$!; exit 0"))
            .redirectErrorStream(true).start()
        val orphan = process.inputStream.bufferedReader().readLine().trim().toLong()
        process.waitFor(5, TimeUnit.SECONDS)

        killGroup(process.pid())

        val child = ProcessHandle.of(orphan)
        child.ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
        assertFalse(child.map { it.isAlive }.orElse(false))
    }

    @Test
    fun `collecting a tree copies its files and skips the links in it`() {
        val root = Files.createTempDirectory("lab")
        val source = root.resolve("output").resolve("config").createDirectories().parent
        source.resolve("config").resolve("p.json").writeText("{}")
        Files.createSymbolicLink(source.resolve("cache"), root.resolve("elsewhere").createDirectories())

        copyTree(source, root.resolve("collected"))

        assertTrue(
            root.resolve("collected/config/p.json").exists() &&
                !Files.exists(root.resolve("collected/cache"), java.nio.file.LinkOption.NOFOLLOW_LINKS),
        )
    }

    /** A runner over a fresh lab, its store and a job of [commands] taken and set up, ready for [Runner.dispatch]. */
    private class Dispatchable(vararg commands: Pair<String, Long>) {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.dataDir.resolve("lab.db"))
        val id = store.create("kills", "main", commands.toList())
        val dir: Path = config.jobDir(id).createDirectories()
        val runner = Runner(config, store)

        init {
            config.worktree(id).createDirectories()
            store.next()
            store.setup(id, "head")
        }

        fun dispatch(): Dispatched = runner.dispatch(checkNotNull(store.job(id)), dir)

        fun reasons(index: Int): List<String> = Termination.read(dir, index).map { it.substringAfter(' ') }
    }

    private fun eventually(check: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            if (check()) return true
            Thread.sleep(50)
        }
        return check()
    }

    @Test
    fun `a timeout kill says so, a SIGKILL the lab did not send is external, and an ordinary failure says nothing`() {
        val lab = Dispatchable("sleep 30" to 1L, "kill -9 \$\$" to 60L, "exit 3" to 60L)

        assertEquals(Dispatched.FINISHED, lab.dispatch())

        assertEquals(listOf(Termination.timeout(1)), lab.reasons(0))
        assertEquals("137", lab.dir.resolve("1.exit").toFile().readText().trim())
        assertEquals(listOf(Termination.external(137)), lab.reasons(1))
        assertTrue(lab.reasons(2).isEmpty())
    }

    @Test
    fun `a cancelled case says it was cancelled`() {
        val lab = Dispatchable("sleep 30" to 60L)
        val canceller = thread {
            eventually { lab.dir.resolve("0.pid").exists() }
            lab.store.requestCancel(lab.id)
        }

        assertEquals(Dispatched.CANCELLED, lab.dispatch())
        canceller.join()

        assertEquals(listOf(Termination.CANCEL), lab.reasons(0))
    }

    @Test
    fun `a runner shutdown says why it killed the case and records no exit, so the case reruns`() {
        val lab = Dispatchable("sleep 30" to 60L)
        // It never returns: a stopping runner waits for its own end with the case left RUNNING.
        thread(isDaemon = true) { lab.dispatch() }
        assertTrue(eventually { lab.dir.resolve("0.pid").exists() })

        lab.runner.stop()

        assertEquals(listOf(Termination.SHUTDOWN), lab.reasons(0))
        Thread.sleep(500)
        assertFalse(lab.dir.resolve("0.exit").exists())
        assertEquals(Status.RUNNING, lab.store.job(lab.id)!!.commands.single().status)
    }
}
