package com.eignex.lab

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The lab's packages are layers, each depending only on those below it: util ← domain ← store ← analysis (with the
 * checkers and reference solvers beside it) ← capacity and execution ← worker and nomad ← web ← cli. A file reaches the
 * layers it may use by star imports; an import of a layer above, or a sideways one not allowed, is a violation. The
 * violations there are are listed in `architecture-violations.txt`, and the list only shrinks: a new one fails here,
 * and so does one fixed but still listed, so the list is always exactly what is left to fix.
 */
class ArchitectureTest {
    private val rank = mapOf(
        "util" to 0, "domain" to 1, "store" to 2, "analysis" to 3, "checker" to 3, "reference" to 3,
        "capacity" to 4, "execution" to 4, "worker" to 5, "nomad" to 5, "web" to 6, "cli" to 7,
    )

    /** The sideways dependencies allowed between layers of a rank. */
    private val sideways = setOf(
        "worker" to "nomad", "nomad" to "worker", "execution" to "capacity", "capacity" to "execution",
        "checker" to "reference", "reference" to "checker", "checker" to "analysis", "analysis" to "checker",
        "analysis" to "reference",
    )

    private fun allowed(from: String, to: String) = from == to || rank.getValue(to) < rank.getValue(from) || (from to to) in sideways

    private val root = Path.of("src/main/kotlin/com/eignex/lab")
    private val baseline = Path.of("src/test/resources/architecture-violations.txt")
    private val import = Regex("""^import com\.eignex\.lab\.(\w+)\.(\*|\w+)""")

    private fun violations(): List<String> = Files.walk(root).use { paths ->
        paths.filter { it.extension == "kt" && it.parent != root }.toList()
    }.flatMap { file ->
        val layer = file.parent.name
        file.readLines().mapNotNull { line ->
            val (to, name) = import.find(line)?.destructured ?: return@mapNotNull null
            if (to !in rank || allowed(layer, to)) null else "$layer/${file.name} -> $to.$name"
        }
    }.sorted()

    @Test
    fun `every file is in a layer, and depends on no layer above it but the listed exceptions`() {
        Files.list(root).use { files -> assertEquals(emptyList(), files.filter { it.extension == "kt" }.map { it.name }.toList(), "files outside a layer") }
        // The checkers and reference solvers are a module of their own (lab-solvers), beside the analysis layer.
        Files.list(root).use { dirs ->
            assertEquals(rank.keys - setOf("checker", "reference"), dirs.filter(Files::isDirectory).map { it.name }.toList().toSet(), "layers")
        }
        val listed = baseline.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val found = violations()
        assertEquals(emptyList(), found - listed.toSet(), "new layering violations: depend on a lower layer, or move the declaration down")
        assertEquals(emptyList(), listed - found.toSet(), "fixed violations still listed: remove them from $baseline")
    }
}
