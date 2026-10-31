package com.eignex.lab

import com.eignex.lab.util.*
import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class RegionalBuildArchivesTest {
    private class Archives : BuildArchives {
        val objects = mutableMapOf<String, ByteArray>()
        val gets = mutableListOf<String>()
        override fun size(sha: String) = objects[sha]?.size?.toLong()
        override fun put(source: Path, sha: String) { objects[sha] = Files.readAllBytes(source) }
        override fun get(sha: String, destination: Path) { gets += sha; Files.write(destination, objects.getValue(sha)) }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    @Test fun `the coordinator checks a regional build archive without fetching it and fetches once to read it`() {
        val root = Files.createTempDirectory("regional-artifacts")
        try {
            val archives = Archives()
            val bytes = "built workspace".toByteArray(); val sha = sha(bytes)
            archives.objects[sha] = bytes
            val local = LocalArtifactStore(root.resolve("artifacts"))
            ResultJournal(root.resolve("journal"), RegionalArtifactStore(local, archives, root.resolve("fetches")), start = false).use { store ->
                store.verify(7, sha)
                assertTrue(store.regional(7, sha))
                assertEquals(bytes.size.toLong(), store.size(7, sha))
                assertEquals(emptyList(), archives.gets)
                assertContentEquals(bytes, store.open(7, sha).use { it.readBytes() })
                assertContentEquals(bytes, store.open(7, sha).use { it.readBytes() })
                assertEquals(listOf(sha), archives.gets)
                assertFalse(store.regional(7, sha))
                val missing = sha("absent".toByteArray())
                assertFailsWith<IllegalStateException> { store.verify(7, missing) }
                assertFalse(store.regional(7, missing))
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `a corrupt regional archive is never published locally`() {
        val root = Files.createTempDirectory("regional-corrupt")
        try {
            val archives = Archives()
            val sha = sha("expected".toByteArray())
            archives.objects[sha] = "tampered".toByteArray()
            val store = RegionalArtifactStore(LocalArtifactStore(root.resolve("artifacts")), archives, root.resolve("fetches"))
            assertFails { store.open(3, sha).use { it.readBytes() } }
            assertTrue(store.regional(3, sha))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `checks of a regional build stage the same files from the local source archive`() {
        val commit = "a".repeat(40)
        fun file(path: String, text: String) = InputFile(path, sha(text.toByteArray()), text.length.toLong())
        fun bundle(sha: Char, vararg files: InputFile) = WorkerBundle(sha.toString().repeat(64), files.toList(), "Eignex/klause", commit, "linux", "amd64", "jdk25")
        val checked: (String) -> Boolean = { it.startsWith("models/") }
        val built = bundle('b', file("models/m.mzn", "model"), file("build/app.jar", "jar"))
        val source = bundle('c', file("models/m.mzn", "model"), file("README", "text"))
        val changed = bundle('d', file("models/m.mzn", "other model"))
        assertEquals(source, checkSource(built, listOf(changed, source), checked) { it == built.sha256 })
        assertEquals(built, checkSource(built, listOf(source), checked) { false })
        assertEquals(built, checkSource(built, listOf(changed), checked) { it == built.sha256 })
    }

    @Test fun `only cloud workers keep build archives in the regional store`() {
        val url = "https://bucket.s3.eu-north-1.amazonaws.com"
        assertNotNull(BuildArchives.worker(mapOf("LAB_SHARED_INPUT_URL" to url)))
        assertNull(BuildArchives.worker(mapOf("LAB_SHARED_INPUT_URL" to url, "LAB_SHARED_INPUT_SIGNER" to "http://127.0.0.1:8093/")))
        assertNull(BuildArchives.worker(emptyMap()))
        assertNotNull(BuildArchives.coordinator(mapOf("LAB_SHARED_INPUT_URL" to url, "LAB_SHARED_INPUT_SIGNER" to "http://127.0.0.1:8093/")))
        assertNull(BuildArchives.coordinator(mapOf("LAB_SHARED_INPUT_URL" to url)))
    }
}
