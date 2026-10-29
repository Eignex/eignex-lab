package com.eignex.lab

import java.nio.file.Files
import kotlin.test.*

class NativePriorityTest {
    private val store = Store(Files.createTempDirectory("native-priority").resolve("lab.db"))
    private val priority = NativePriority(store)
    private val tags = setOf("local", "arch:arm64", "platform:mac")
    private fun job(name: String, value: Int = 0, host: HostPlacement = Experiments.AUTO_HOST, selector: NodeSelector? = null): Long =
        store.create(name, "main", emptyList(), priority = value,
            experiment = ExperimentSpec(name, problems = listOf(mapOf("suite" to "s")), host = host, nodeTags = selector))

    @Test fun `equal automatic jobs and equal pinned jobs do not preempt each other`() {
        val first = job("first")
        val second = job("second")
        assertTrue(priority.allows(first, tags)); assertTrue(priority.allows(second, tags))
        val pinned = job("sweep@a", host = Experiments.LAB_HOST)
        val other = job("sweep@b", host = Experiments.LAB_HOST)
        assertTrue(priority.allows(pinned, tags)); assertTrue(priority.allows(other, tags))
        assertFalse(priority.allows(first, tags), "pinned jobs retain preference over automatic work at equal priority")
    }

    @Test fun `priority edits in either direction affect the next native claim`() {
        val first = job("first")
        val second = job("second")
        store.setPriority(second, 1)
        assertFalse(priority.allows(first, tags)); assertTrue(priority.allows(second, tags))
        store.setPriority(second, -1)
        assertTrue(priority.allows(first, tags)); assertFalse(priority.allows(second, tags))
    }

    @Test fun `paused cancelled and finished jobs do not preempt`() {
        val current = job("current")
        val waiting = job("waiting", 1)
        store.setPaused(waiting, true)
        assertTrue(priority.allows(current, tags))
        store.setPaused(waiting, false)
        assertFalse(priority.allows(current, tags))
        store.requestCancel(waiting)
        assertTrue(priority.allows(current, tags))
        val finished = job("finished", 1)
        store.finish(finished, Status.DONE)
        assertTrue(priority.allows(current, tags))
    }

    @Test fun `cloud only jobs and incompatible tags do not preempt native work`() {
        val current = job("current")
        job("cloud", 1, host = Experiments.AWS_HOST)
        job("x86", 1, selector = NodeSelector(require = setOf("arch:x86_64")))
        job("exclude-local", 1, selector = NodeSelector(exclude = setOf("local")))
        assertTrue(priority.allows(current, tags))
        job("arm", 1, selector = NodeSelector(require = setOf("arch:arm64")))
        assertFalse(priority.allows(current, tags))
    }

    @Test fun `a prepared higher priority job with no unclaimed cases does not preempt`() {
        val current = job("current")
        val waiting = job("waiting", 1)
        val spec = store.job(waiting)!!.experiment!!
        val arms = Experiments.arms(spec).map { PlannedArm(it, "a".repeat(40)) }
        store.plan(waiting, arms, listOf(Problem("s", "p")), Experiments.cases(1, arms.size, spec.seeds), listOf("true" to 5L))
        store.setup(waiting, "a".repeat(40))
        assertFalse(priority.allows(current, tags))
        store.commandStarted(waiting, 0, "another-host")
        assertTrue(priority.allows(current, tags))
        store.commandFinished(waiting, 0, 0)
        assertTrue(priority.allows(current, tags), "pending result checks must not block native work")
    }
}
