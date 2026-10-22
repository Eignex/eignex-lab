package com.eignex.lab.checker

import com.eignex.lab.Problem
import java.io.File
import java.util.concurrent.TimeUnit

/** The klause commit a case ran, [sha], as its arm pinned it in job [job]: what a repository fixture is read at. */
data class Revision(val job: Long, val sha: String)

/**
 * Finds the source of a case whose problem is a file of the klause repository itself, such as the bench's
 * `klause-bench/smoke-corpus`, rather than of the corpus cache. The file is read at the case's own pinned commit: from
 * the job's worktree of that commit while it still exists ([worktrees]), else from the lab's git [mirror], written into
 * the check's scratch directory and deleted with it. A file counts only when its plain bytes hash to the record's
 * `sourceHashes`, as a corpus file must; a file that does not, or a path the commit lacks, leaves the case unchecked,
 * and nothing falls back to the file as it is on any other commit.
 */
class RepositoryFixtures(
    private val mirror: File,
    private val worktrees: (Revision) -> List<File>,
    /** The directories worktrees live in, on the lab and on a cloud node: a planned path under one is a repository path. */
    private val worktreeRoots: List<String>,
    private val hash: (File) -> String = ::sha256,
) {
    /** Whether [problem]'s source is a file of the klause repository rather than of the corpus. */
    fun owns(problem: Problem): Boolean = problem.collection.startsWith(REPOSITORY_PREFIX) || problem.model?.let(::relative) != null

    /** [problem]'s model at [revision], with plain-byte digest [modelHash]; anything read from the mirror goes in [scratch]. */
    fun locate(problem: Problem, modelHash: String?, revision: Revision?, scratch: File): SourceFiles {
        checkNotNull(revision) { "the case names no pinned klause commit to read its repository file at" }
        checkNotNull(modelHash) { "the record names no source hash to verify its repository file with" }
        check(problem.data == null) { "a repository source with a data file is not supported" }
        val paths = problem.model?.let { model ->
            listOf(checkNotNull(relative(model) ?: underCollection(model, problem.collection)) {
                "$model is not a path in a klause worktree"
            })
        } ?: named(problem, revision)
        val short = revision.sha.take(SHORT)
        check(paths.isNotEmpty()) { "no file for ${problem.problem} in ${problem.collection} at klause $short" }
        val found = paths.firstNotNullOfOrNull { path -> fromWorktree(path, revision, modelHash) ?: fromMirror(path, revision, modelHash, scratch) }
        return SourceFiles(found ?: error("no file for ${problem.problem} at klause $short has the solved model's SHA-256 $modelHash (${paths.joinToString()})"))
    }

    /** The repository path of [path], a file under one of [worktreeRoots]'s worktrees, or null. */
    fun relative(path: String): String? = worktreeRoots.firstNotNullOfOrNull { root ->
        path.removePrefix("${root.trimEnd('/')}/").takeIf { it != path }?.substringAfter('/', "")?.takeIf { it.isNotEmpty() }
    }

    /** [path] from [collection] on, for a worktree on a host whose root this lab does not know. */
    private fun underCollection(path: String, collection: String): String? =
        collection.takeIf { it.startsWith(REPOSITORY_PREFIX) }?.let { path.indexOf("/$it/") }?.takeIf { it >= 0 }?.let { path.substring(it + 1) }

    /** The repository paths in [problem]'s collection at [revision] named as the problem is. */
    private fun named(problem: Problem, revision: Revision): List<String> {
        val collection = problem.collection.trimEnd('/')
        check(collection.startsWith(REPOSITORY_PREFIX)) { "collection $collection is not a klause repository path" }
        val last = problem.problem.substringAfterLast('/')
        val names = setOf(SourceLocator.stem(last), last)
        val listed = worktree(revision, collection)?.let { dir ->
            dir.walkTopDown().filter { it.isFile }.map { "$collection/${it.relativeTo(dir).invariantSeparatorsPath}" }.toList()
        } ?: git("ls-tree", "-r", "--name-only", revision.sha, "--", "$collection/").let { (exit, out) ->
            check(exit == 0) { "klause ${revision.sha.take(SHORT)} is not in the lab's mirror: ${out.trim().take(200)}" }
            out.lines().filter { it.isNotBlank() }
        }
        return listed.filter { SourceLocator.stem(it.substringAfterLast('/')) in names }.sorted()
    }

    private fun worktree(revision: Revision, path: String): File? = worktrees(revision).map { File(it, path) }.firstOrNull { it.exists() }

    private fun fromWorktree(path: String, revision: Revision, expected: String): File? =
        worktree(revision, path)?.takeIf { it.isFile && hash(it) == expected }

    /** [path] at [revision] from the mirror, written under [scratch], when its bytes hash to [expected]. */
    private fun fromMirror(path: String, revision: Revision, expected: String, scratch: File): File? {
        val dir = File(scratch, "source").apply { mkdirs() }
        val file = File(dir, path.substringAfterLast('/'))
        val (exit, err) = git("cat-file", "blob", "${revision.sha}:$path", output = file)
        if (exit != 0) {
            file.delete()
            error("$path is not in klause at ${revision.sha.take(SHORT)}: ${err.trim().take(200)}")
        }
        if (hash(file) == expected) return file
        file.delete()
        return null
    }

    /** `git` on the mirror: its exit, and its output, or only its errors when [output] takes the output. */
    private fun git(vararg args: String, output: File? = null): Pair<Int, String> {
        val builder = ProcessBuilder(listOf("git", "--git-dir", mirror.path) + args)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        if (output != null) builder.redirectOutput(output) else builder.redirectErrorStream(true)
        val process = builder.start()
        val text = (if (output != null) process.errorStream else process.inputStream).bufferedReader().use { it.readText() }
        if (!process.waitFor(GIT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("git ${args.first()} on the mirror took over $GIT_TIMEOUT_SEC s")
        }
        return process.exitValue() to text
    }

    companion object {
        /** Where the bench keeps the problems it carries in the repository, such as `klause-bench/smoke-corpus`. */
        const val REPOSITORY_PREFIX = "klause-bench/"
        private const val SHORT = 12
        private const val GIT_TIMEOUT_SEC = 60L
    }
}
