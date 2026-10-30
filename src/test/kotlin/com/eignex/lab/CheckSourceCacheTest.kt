package com.eignex.lab

import com.eignex.lab.util.*
import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.nomad.*
import com.eignex.lab.execution.*

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class CheckSourceCacheTest {
    private val root = Files.createTempDirectory("check-sources")

    private fun bundle(commit: Char, files: Map<String, String>): Pair<WorkerBundle, ByteArray> {
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            files.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
        } }.toByteArray()
        return WorkerBundle(MessageDigest.getInstance("SHA-256").digest(bytes).hex(), files.map { (name, text) ->
            InputFile(name, ExecutionStore.digest(text), text.toByteArray().size.toLong())
        }, Repositories.DEFAULT, commit.toString().repeat(40), "linux", "amd64", "fixture") to bytes
    }

    @Test fun `selected staging keeps only checked files and shares a tree across commits that agree on them`() {
        val select = checkNotNull(checkedSourcePaths(listOf(
            Problem("bench", "p", model = "lab-source/models/p.mzn", data = "lab-source/data/p.dzn", includeDirs = listOf("lab-source/lib/")),
            Problem("bench", "q", model = "lab-corpus/q.mzn"),
        )))
        val shared = mapOf("models/p.mzn" to "model", "data/p.dzn" to "data", "lib/globals.mzn" to "globals")
        val (first, firstBytes) = bundle('a', shared + mapOf("build/app.jar" to "one", "src/Main.kt" to "one"))
        val (second, secondBytes) = bundle('b', shared + mapOf("build/app.jar" to "two", "src/Main.kt" to "two"))
        val cache = VerifiedCache(root)

        val a = cache.stageSelected(first, select) { ByteArrayInputStream(firstBytes) }
        val b = cache.stageSelected(second, select) { ByteArrayInputStream(secondBytes) }

        assertEquals(a, b)
        val kept = Files.walk(a).use { paths -> paths.filter(Files::isRegularFile).map { a.relativize(it).toString() }.sorted().toList() }
        assertEquals(listOf("data/p.dzn", "lib/globals.mzn", "models/p.mzn"), kept)
    }

    @Test fun `repository collections are kept whole and corpus-only problems select nothing`() {
        val select = checkNotNull(checkedSourcePaths(listOf(Problem("bench", "s", collection = "klause-bench/smoke-corpus/"))))
        assertTrue(select("klause-bench/smoke-corpus/a/s.mzn"))
        assertFalse(select("klause-bench/smoke-corpus-other/s.mzn"))
        assertFalse(select("klause/build/libs/klause.jar"))
        assertNull(checkedSourcePaths(listOf(Problem("bench", "q", model = "lab-corpus/q.mzn"))))
    }
}
