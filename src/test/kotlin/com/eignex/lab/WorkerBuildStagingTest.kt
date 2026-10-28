package com.eignex.lab

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.*

class WorkerBuildStagingTest {
    @Test fun `cleanup protects live local and foreign pins and reclaims crash and legacy staging`() {
        val root = Files.createTempDirectory("build-staging")
        val storage = WorkerBuildStaging(root)
        val live = storage.create({})
        val crashed = storage.create({}).also { it.close() }
        val old = Files.createDirectory(root.resolve(".build-100"))
        val recent = Files.createDirectory(root.resolve(".build-101"))
        val foreign = Files.createDirectory(root.resolve(".build-102"))
        val process = ProcessBuilder("python3", "-c", """
            import fcntl,sys
            with open(sys.argv[1], 'a') as pin:
                fcntl.lockf(pin, fcntl.LOCK_EX)
                print('ready', flush=True)
                sys.stdin.readline()
        """.trimIndent(), foreign.resolve("owner.lock").toString()).start()
        try {
            assertEquals("ready", process.inputStream.bufferedReader().readLine())
            val at = now() + 10L*60*1000
            Files.setLastModifiedTime(old, FileTime.fromMillis(at - 25L*60*60*1000))
            Files.writeString(old.resolve("partial"), "abandoned")
            // Writing the child updates directory mtime, so age it after populating it.
            Files.setLastModifiedTime(old, FileTime.fromMillis(at - 25L*60*60*1000))
            val cache = WorkerBuildCache(root, ProcessSupervisor(root.resolve("unused")))
            cache.maintain(at)
            assertTrue(Files.exists(crashed.directory.resolve("orphaned.at"))); assertFalse(Files.exists(old))
            assertTrue(Files.exists(live.directory)); assertTrue(Files.exists(recent)); assertTrue(Files.exists(foreign))
            // A same-process probe must not release the OS lock held by this active build.
            val probe = ProcessBuilder("python3", "-c", """
                import fcntl,sys
                with open(sys.argv[1], 'r+') as pin:
                    try: fcntl.lockf(pin, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    except BlockingIOError: sys.exit(73)
            """.trimIndent(), live.directory.resolve("owner.lock").toString()).start()
            assertEquals(73, probe.waitFor())
            process.outputStream.write(10); process.outputStream.flush(); assertEquals(0, process.waitFor())
            cache.maintain(at + 61_000)
            assertTrue(Files.exists(foreign)); assertTrue(Files.exists(crashed.directory))
            cache.maintain(at + 7L*60*1000)
            assertFalse(Files.exists(foreign)); assertFalse(Files.exists(crashed.directory)); assertTrue(Files.exists(live.directory))
            val destination = root.resolve("a".repeat(64))
            storage.publish(live, destination, {})
            storage.close(live)
            cache.maintain(at + 8L*60*1000)
            assertTrue(Files.isDirectory(destination))
        } finally { process.destroyForcibly(); live.close(); deleteTree(root) }
    }

    @Test fun `cleanup ignores symlinks and resumes deletion of abandoned tombstones`() {
        val root = Files.createTempDirectory("staging-links")
        val outside = Files.createTempDirectory("staging-outside")
        try {
            Files.writeString(outside.resolve("keep"), "safe")
            Files.createSymbolicLink(root.resolve(".build-1"), outside)
            val trash = Files.createDirectory(root.resolve(".build-trash-interrupted"))
            Files.writeString(trash.resolve("partial"), "old")
            WorkerBuildCache(root, ProcessSupervisor(root.resolve("unused"))).maintain()
            assertTrue(Files.exists(outside.resolve("keep")))
            assertFalse(Files.exists(trash)); assertTrue(Files.isSymbolicLink(root.resolve(".build-1")))
        } finally { deleteTree(root); deleteTree(outside) }
    }
}
