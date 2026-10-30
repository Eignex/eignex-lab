package com.eignex.lab.worker

import com.eignex.lab.util.ProcessFilePin
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Process-owned tickets prevent later cold builds from overtaking a waiting compiler. */
internal class CompilerAdmission(private val root: Path) {
    private val waiting = root.resolve("compile-waiters")

    internal class Ticket(val path: Path, private val pin: ProcessFilePin) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) {
                pin.close()
                Files.deleteIfExists(path)
            }
        }
    }

    fun <T> use(slots: Int, checkActive: () -> Unit, action: () -> T): T {
        require(slots in 1..64)
        register(checkActive).use { ticket ->
            while (true) {
                checkActive()
                acquire(ticket, slots, checkActive)?.use { return action() }
                Thread.sleep(50)
            }
        }
    }

    internal fun register(checkActive: () -> Unit = {}): Ticket = locked(checkActive) {
        val sequence = live().lastOrNull()?.fileName?.toString()?.substringBefore('-')?.toLong()?.plus(1) ?: 1
        val path = waiting.resolve("${sequence.toString().padStart(20, '0')}-${UUID.randomUUID()}.wait")
        Ticket(path, requireNotNull(ProcessFilePin.tryLock(path)))
    }

    internal fun acquire(ticket: Ticket, slots: Int, checkActive: () -> Unit = {}): ProcessFilePin? = locked(checkActive) {
        require(slots in 1..64)
        if (live().firstOrNull() != ticket.path) return@locked null
        for (slot in 0 until slots) {
            val pin = ProcessFilePin.tryLock(root.resolve("compile-$slot.lock")) ?: continue
            try { ticket.close() } catch (failure: Exception) { pin.close(); throw failure }
            return@locked pin
        }
        null
    }

    private fun live(): List<Path> = Files.list(waiting).use { paths ->
        paths.filter { it.fileName.toString().matches(TICKET) }.sorted().toList().filter { path ->
            val stale = ProcessFilePin.tryLock(path)
            if (stale == null) true else { stale.close(); Files.deleteIfExists(path); false }
        }
    }

    private fun <T> locked(checkActive: () -> Unit, action: () -> T): T {
        Files.createDirectories(waiting)
        while (true) {
            checkActive()
            ProcessFilePin.tryLock(root.resolve("compile-admission.lock"))?.use { return action() }
            Thread.sleep(10)
        }
    }

    companion object {
        private val TICKET = Regex("[0-9]{20}-[0-9a-f-]{36}\\.wait")
    }
}
