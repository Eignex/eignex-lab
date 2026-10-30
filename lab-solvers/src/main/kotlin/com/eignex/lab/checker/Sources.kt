package com.eignex.lab.checker

import com.eignex.lab.domain.*
import com.eignex.lab.reference.*

import com.eignex.lab.domain.Problem
import com.eignex.lab.reference.CorpusFiles
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The original source files of one case, as found in the lab's corpus. */
data class SourceFiles(val model: File, val data: File? = null, val includeDirs: List<File> = emptyList())

/**
 * Finds a case's original source files in the lab's own corpus, [corpus], whichever host ran the case: the paths a
 * plan resolved (`select files=true`), moved from another host's corpus root ([foreignRoots]) to this one, else a
 * search of the problem's collection by file name. A file counts only when its plain bytes hash to what the case's
 * record says it solved (`sourceHashes`), so a renamed, edited or recompressed corpus can never be checked against
 * in its place. A MiniZinc model with data is found only as a pair whose model and data both hash to the record's
 * `model` and `data`, never one without the other. Nothing here downloads: a collection the lab's corpus lacks leaves the case unchecked. A problem the
 * klause repository itself carries, such as the bench's smoke corpus, is read at the case's pinned commit by
 * [fixtures] instead, never from the corpus.
 */
class SourceLocator(
    private val corpus: File,
    private val foreignRoots: List<String> = listOf(NODE_CORPUS),
    private val hash: (File) -> String = CorpusFiles::sha256,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fixtures: RepositoryFixtures? = null,
    private val problemPaths: (Problem, Revision?) -> Problem = { problem, _ -> problem },
) {
    private val indexes = ConcurrentHashMap<String, Pair<Long, Map<String, List<File>>>>()

    /**
     * [problem]'s files with the plain-byte digests [hashes] (`model`, and `data` for MiniZinc); a failure says why.
     * A model with a data file is found as a pair: both files must hash to what the record says, and neither is ever
     * taken without the other. A repository file is read at [revision], into [scratch] when no worktree of that commit
     * holds it.
     */
    fun locate(requested: Problem, hashes: Map<String, String>, revision: Revision? = null, scratch: File? = null): Result<SourceFiles> = runCatching {
        val problem = problemPaths(requested, revision)
        val modelHash = hashes["model"]
        val dataHash = hashes["data"]
        val paired = dataHash != null || problem.data != null
        if (paired) {
            checkNotNull(modelHash) { "the record names no model hash to verify ${problem.problem}'s model with" }
            checkNotNull(dataHash) { "the record names no data hash to verify ${problem.problem}'s data file with" }
        }
        if (fixtures != null && fixtures.owns(problem)) {
            return@runCatching fixtures.locate(problem, modelHash, dataHash, revision,
                checkNotNull(scratch) { "no scratch directory to read a repository file into" })
        }
        val planned = problem.model?.let { model ->
            val m = moved(model).firstOrNull { matches(it, modelHash) } ?: return@let null
            val includes = problem.includeDirs.flatMap(::moved).filter { it.isDirectory }.distinct()
            if (!paired) return@let SourceFiles(m, includeDirs = includes)
            val d = problem.data?.let { data -> moved(data).firstOrNull { matches(it, dataHash) } } ?: return@let null
            SourceFiles(m, d, includes)
        }
        planned ?: search(problem, modelHash, dataHash)
    }

    private fun search(problem: Problem, modelHash: String?, dataHash: String?): SourceFiles {
        val collection = problem.collection.ifEmpty { problem.suite }
        val root = File(corpus, collection)
        check(root.isDirectory) { "collection $collection is not in the lab's corpus ($corpus)" }
        // A name may keep a suffix its file's stem does too (`B737NG-600-01-Anon.json` for `….json.dzn`).
        val last = problem.problem.substringAfterLast('/')
        val named = listOf(stem(last), last).distinct().flatMap { candidates(collection, root, it) }.distinct()
        val includes = problem.includeDirs.flatMap(::moved).filter { it.isDirectory }.ifEmpty { libraries(root) }
        if (dataHash == null) {
            val model = pick(named, modelHash, "model of ${problem.problem}", root)
            return SourceFiles(model, includeDirs = includes)
        }
        return pair(problem, named, checkNotNull(modelHash), dataHash, root, includes)
    }

    /**
     * The model and data of [problem] as one pair: a data file of its name hashing to [dataHash], and a model hashing
     * to [modelHash] in that file's directory or one above it, up to [root]. Every data file with the solved bytes is
     * tried, whatever its path's order, since the same data can sit under an earlier family or year whose model is
     * another (sweep 7671's `2013/ghoulomb/3-9-16` beside `2010/ghoulomb/3-9-16.dzn`).
     */
    private fun pair(problem: Problem, named: List<File>, modelHash: String, dataHash: String, root: File, includes: List<File>): SourceFiles {
        check(named.isNotEmpty()) { "no file for the data of ${problem.problem} in $root" }
        val digests = HashMap<File, String>()
        fun digest(file: File) = digests.getOrPut(file) { hash(file) }
        val data = named.filter { digest(it) == dataHash }
        check(data.isNotEmpty()) { "no file in $root has the solved data of ${problem.problem}'s SHA-256 $dataHash" }
        val searched = HashSet<File>()
        for (d in data) {
            val dirs = generateSequence(d.parentFile) { dir -> dir.parentFile?.takeIf { dir != root } }
            for (dir in dirs) {
                if (!searched.add(dir)) continue
                val model = dir.listFiles { f -> f.isFile && stem(f.name) != f.name && f.name.removeSuffix(".zst").endsWith(".mzn") }
                    .orEmpty().sorted().firstOrNull { digest(it) == modelHash }
                if (model != null) return SourceFiles(model, d, includes)
            }
        }
        error("no model with the solved model of ${problem.problem}'s SHA-256 $modelHash is beside or above any of the " +
            "${data.size} data file(s) in $root with the solved data's SHA-256 $dataHash (${data.joinToString { it.relativeTo(root).path }})")
    }

    private fun pick(files: List<File>, expected: String?, what: String, root: File): File {
        check(files.isNotEmpty()) { "no file for the $what in $root" }
        if (expected == null) {
            check(files.size == 1) { "the record names no source hash and ${files.size} files could be the $what" }
            return files.single()
        }
        return files.firstOrNull { hash(it) == expected }
            ?: error("no file in $root has the solved $what's SHA-256 $expected")
    }

    private fun matches(file: File, expected: String?) = file.isFile && (expected == null || hash(file) == expected)

    /** [path] as this corpus holds it: itself, or under [corpus] when it was under another host's root; each plain or
     *  zstd-compressed, as the corpus keeps either. */
    private fun moved(path: String): List<File> {
        val roots = foreignRoots + corpus.path
        val relative = roots.firstNotNullOfOrNull { root -> path.removePrefix("$root/").takeIf { it != path } }
        val base = if (relative != null) listOf(File(corpus, relative), File(path)) else listOf(File(path))
        return base.flatMap { f -> listOf(f, if (f.name.endsWith(".zst")) File(f.path.removeSuffix(".zst")) else File(f.path + ".zst")) }
            .distinct().filter { it.exists() }
    }

    /** The files of [collection] named [name], from an index of it built at most once per [REINDEX_MS] when one is missing. */
    private fun candidates(collection: String, root: File, name: String): List<File> {
        val now = clock()
        val cached = indexes[collection]
        val index = if (cached == null || (name !in cached.second && now - cached.first > REINDEX_MS)) {
            val built = root.walkTopDown().filter { it.isFile }.groupBy { stem(it.name) }
            indexes[collection] = now to built
            built
        } else cached.second
        return index[name].orEmpty().sortedBy { it.path }
    }

    /** A MiniZinc collection's shared library directories, for `-I`: any `lib` or `include` near its root. */
    private fun libraries(root: File): List<File> = root.walkTopDown().maxDepth(2)
        .filter { it.isDirectory && it.name in setOf("lib", "include") }.toList().sorted()

    companion object {
        /** A cloud node's corpus root (historical cloud workers), which a plan made there names its files under. */
        const val NODE_CORPUS = "/home/ubuntu/corpus"
        const val REINDEX_MS = 10 * 60_000L
        private val COMPRESSION = listOf(".zst", ".gz", ".lzma", ".xz", ".bz2")
        private val EXTENSIONS = listOf(".xml", ".mps", ".smt2", ".dzn", ".json", ".mzn", ".cnf", ".opb", ".wcnf", ".lp")

        /** A corpus file's name without its compression and format suffixes: the name a problem goes by. */
        fun stem(name: String): String {
            var s = name
            COMPRESSION.firstOrNull { s.endsWith(it) }?.let { s = s.removeSuffix(it) }
            EXTENSIONS.firstOrNull { s.endsWith(it) }?.let { s = s.removeSuffix(it) }
            return s
        }
    }
}
