package com.eignex.lab.checker

import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * The MIPLIB 2017 solution checker (`solchecker`, from `miplib2017-testscript-v1.0.4.zip`, MIT, Zuse Institute Berlin),
 * built once from the archive pinned by [ARCHIVE_SHA256] and kept as the one binary under `<dir>/solchecker-1.0.4/`.
 * The archive comes from the lab's own mirror, a klause-lab release asset holding the unmodified upstream bytes, and
 * from miplib.zib.de only when the mirror cannot be reached; either is checked against the pin. The archive and the
 * build tree are deleted once the binary is built. A build that fails is not tried again for [RETRY_MS], so a broken
 * toolchain or an outage costs each check a message, not a build.
 */
class Solchecker(
    private val dir: File,
    private val urls: List<String> = listOf(MIRROR_URL, UPSTREAM_URL),
    private val compiler: String = "c++",
    /** Where the compiler finds GMP and zlib besides the system's. */
    private val flags: List<String> = INCLUDES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var failure: Pair<Long, String>? = null

    /** The built binary, building it first if it is not there; throws, saying why, when it cannot be had. */
    @Synchronized
    fun binary(): File {
        val home = File(dir, "solchecker-$VERSION")
        val binary = File(home, "solchecker")
        if (binary.canExecute()) return binary
        failure?.let { (at, why) -> if (clock() - at < RETRY_MS) error(why) }
        try {
            build(home, binary)
            failure = null
            return binary
        } catch (e: Exception) {
            val why = "the MIPLIB solchecker could not be built: ${e.message}"
            failure = clock() to why
            error(why)
        }
    }

    private fun build(home: File, binary: File) {
        val tmp = File(dir, "tmp").apply { mkdirs() }
        val work = Files.createTempDirectory(tmp.toPath(), "solchecker-build-").toFile()
        try {
            val archive = File(work, "miplib2017-testscript-v$VERSION.zip")
            val errors = mutableListOf<String>()
            val fetched = urls.any { url ->
                runCatching { download(url, archive) }.onFailure { errors += "$url: ${it.message}" }.isSuccess
            }
            check(fetched) { "the pinned archive could not be fetched (${errors.joinToString("; ")})" }
            val sources = File(work, "src").apply { mkdirs() }
            ZipFile(archive).use { zip ->
                for (name in SOURCES + HEADERS) {
                    val entry = checkNotNull(zip.getEntry("checker/src/$name")) { "checker/src/$name is not in the archive" }
                    zip.getInputStream(entry).use { input -> File(sources, name).outputStream().use { input.copyTo(it) } }
                }
            }
            val out = File(work, "solchecker")
            val command = listOf(compiler, "-O2") + flags + listOf("-o", out.path) + SOURCES.map { File(sources, it).path } + listOf("-lgmp", "-lz")
            val run = ToolProcess.run(command, work, BUILD_TIMEOUT_MS)
            check(run.exit == 0 && out.isFile) { "${command.first()} failed (exit ${run.exit}): ${run.tail()}" }
            home.mkdirs()
            Files.move(out.toPath(), binary.toPath(), StandardCopyOption.REPLACE_EXISTING)
            check(binary.setExecutable(true, true)) { "the built solchecker cannot be made executable" }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun download(url: String, archive: File) {
        val connection = URI(url).toURL().openConnection().apply { connectTimeout = 30_000; readTimeout = 30_000 }
        connection.getInputStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
        val sha = sha256(archive)
        check(sha == ARCHIVE_SHA256) { "SHA-256 $sha differs from the pin" }
    }

    companion object {
        const val VERSION = "1.0.4"
        const val ARCHIVE_SHA256 = "37b21a7f1ee968aed60675fb070c9286eccc6a953c8bc50120c9246b052b47b9"
        /** The lab's mirror of the upstream archive, byte for byte (release `upstream-miplib2017-testscript-v1.0.4`). */
        const val MIRROR_URL = "https://github.com/Eignex/klause-lab/releases/download/upstream-miplib2017-testscript-v1.0.4/" +
            "miplib2017-testscript-v1.0.4.zip"
        const val UPSTREAM_URL = "https://miplib.zib.de/downloads/miplib2017-testscript-v1.0.4.zip"
        const val RETRY_MS = 10 * 60_000L
        private const val BUILD_TIMEOUT_MS = 300_000L
        private val SOURCES = listOf("main.cpp", "gmputils.cpp", "model.cpp", "mpsinput.cpp")
        private val HEADERS = listOf("gmputils.h", "model.h", "mpsinput.h")
        /** GMP and zlib where Homebrew (the Mac) or a source install keeps them; the system's are found without. */
        val INCLUDES = listOf("-I/opt/homebrew/include", "-L/opt/homebrew/lib", "-I/usr/local/include", "-L/usr/local/lib")
    }
}

/**
 * MPS witnesses, checked by the MIPLIB 2017 solchecker ([Solchecker]) against the original MPS file as it is: nothing
 * of the model is rewritten. The witness is the record's final `v name=value …` line, written as a SOL file with the
 * claimed objective as `=obj=`; solchecker checks bounds, integrality, every row and the objective in exact rational
 * arithmetic with its default tolerances, 1e-5 absolute for rows, objective and integrality alike (the code's default;
 * its README still says 1e-4). solchecker reads only decimals of under 32 characters, so a value with no such exact
 * decimal (a fraction like 1/3) leaves the claim unsupported rather than checked against a rounding of it. A file
 * solchecker cannot read, or a run it aborts, is unsupported in its own words.
 */
class MpsChecker(private val solchecker: () -> File, private val timeoutMs: Long = TIMEOUT_MS) : FormatChecker {
    override val format = "MPS"
    override val policy = Policies.MPS
    override val checker = "MIPLIB 2017 solchecker"

    override fun check(case: CheckCase, work: File): Verdict {
        val binary = solchecker()
        val sha = sha256(binary)
        fun verdict(state: String, reason: String) = Verdict(state, reason, Solchecker.VERSION, sha)
        val objective = case.claimedObjective ?: return verdict(LabCheck.UNKNOWN, "the record holds no claimed objective to check")
        val sol = runCatching { solution(case.witness, objective) }.getOrElse { return verdict(LabCheck.UNSUPPORTED, it.message.orEmpty()) }
        val file = File(work, "witness.sol").apply { writeText(sol) }
        val model = case.sources.model
        val run = if (model.name.endsWith(".zst")) {
            // Decompressed on the fly, so no plain copy of a large model lands on disk.
            ToolProcess.run(listOf("/bin/sh", "-c", "zstd -dcq \"$1\" | exec \"$2\" /dev/stdin \"$3\"", "solchecker",
                model.path, binary.path, file.path), work, timeoutMs)
        } else {
            ToolProcess.run(listOf(binary.path, model.path, file.path), work, timeoutMs)
        }
        if (run.timedOut) return verdict(LabCheck.NOT_RUN, "solchecker did not finish within ${timeoutMs / 1000} s")
        val checked = CHECK.find(run.stdout)
            ?: return verdict(LabCheck.UNSUPPORTED, "solchecker could not check it (exit ${run.exit}): ${run.tail()}")
        val (integrality, constraints, objectiveOk) = checked.destructured
        val violations = run.stdout.lineSequence().firstOrNull { it.startsWith("Maximum violations:") }?.trim().orEmpty()
        if (integrality == "1" && constraints == "1" && objectiveOk == "1") {
            return verdict(LabCheck.VALID, "solchecker accepted the point and its objective $objective. $violations".trim())
        }
        val failed = listOfNotNull("integrality".takeIf { integrality != "1" }, "bounds or constraints".takeIf { constraints != "1" },
            "objective $objective".takeIf { objectiveOk != "1" })
        return verdict(LabCheck.INVALID, "solchecker rejected the ${failed.joinToString(" and ")}. $violations".trim())
    }

    companion object {
        const val TIMEOUT_MS = 120_000L
        private val CHECK = Regex("""Check SOL: Integrality (\d) Constraints (\d) Objective (\d)""")
        private val NUMBER = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?|[+-]?[0-9]+/[0-9]+")
        /** solchecker's own limit: it asserts every number it reads is shorter. */
        private const val MAX_NUMBER = 31

        /** The SOL file of witness `v name=value …` with objective [objective]; throws, saying why, on what solchecker
         *  cannot read exactly. */
        fun solution(witness: String, objective: String): String = buildString {
            val tokens = witness.trim().split(Regex("\\s+"))
            require(tokens.firstOrNull() == "v") { "the witness is not a `v name=value` line" }
            appendLine("=obj= ${decimal(objective)}")
            val seen = HashSet<String>()
            for (token in tokens.drop(1)) {
                val at = token.lastIndexOf('=')
                require(at > 0 && at < token.length - 1) { "malformed coordinate $token" }
                val name = token.substring(0, at)
                require(seen.add(name)) { "variable $name assigned twice" }
                appendLine("$name ${decimal(token.substring(at + 1))}")
            }
        }

        /** [text], a decimal or a fraction, as the exact decimal solchecker reads. */
        fun decimal(text: String): String {
            require(NUMBER.matches(text)) { "value $text is not a number" }
            val value = if ('/' in text) {
                val (p, q) = text.split('/').map(::BigInteger)
                require(q.signum() > 0) { "value $text has no positive denominator" }
                runCatching { BigDecimal(p).divide(BigDecimal(q)) }
                    .getOrElse { throw IllegalArgumentException("value $text has no exact decimal, and solchecker reads decimals only") }
            } else BigDecimal(text)
            val plain = value.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
            val shortest = listOf(plain, value.stripTrailingZeros().toString()).minBy { it.length }
            require(shortest.length <= MAX_NUMBER) { "value $text has no exact decimal under 32 characters, solchecker's input limit" }
            return shortest
        }
    }
}
