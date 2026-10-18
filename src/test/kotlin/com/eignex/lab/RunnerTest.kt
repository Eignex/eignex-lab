package com.eignex.lab

import java.nio.file.Files
import java.util.concurrent.TimeUnit
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
}
