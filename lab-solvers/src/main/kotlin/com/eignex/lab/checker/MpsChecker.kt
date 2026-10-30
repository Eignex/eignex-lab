package com.eignex.lab.checker

import com.eignex.lab.domain.*
import com.eignex.lab.reference.*

import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * The MIPLIB 2017 solution checker (`solchecker`, from `miplib2017-testscript-v1.0.4.zip`, MIT, Zuse Institute Berlin),
 * built once from the archive pinned by [ARCHIVE_SHA256] with the lab's patch [PATCH] applied, and kept as the one
 * binary under `<dir>/solchecker-<VERSION>/`. The patch, pinned by [PATCH_SHA256], only raises three fixed input
 * limits: a number may be 4095 characters rather than 31, the I/O buffer it is expanded into 16384 bytes rather than
 * 1024, and a SOL line 8190 characters rather than 1022. Parsing and the exact GMP arithmetic are upstream's. The
 * archive is bundled with the lab as the resource [ARCHIVE], the unmodified upstream bytes, so the build needs no
 * network; it is checked against the pin before it is unpacked. The build tree is deleted once the binary is built. A
 * build that fails is not tried again for [RETRY_MS], so a broken toolchain costs each check a message, not a build.
 */
class Solchecker(
    private val dir: File,
    /** The bundled upstream archive, a resource beside this class. */
    private val archive: String = ARCHIVE,
    private val compiler: String = "c++",
    private val patcher: String = "patch",
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
            val zipped = File(work, ARCHIVE)
            checkNotNull(Solchecker::class.java.getResourceAsStream(archive)) { "the archive $archive is not packaged" }
                .use { input -> zipped.outputStream().use { input.copyTo(it) } }
            val sha = sha256(zipped)
            check(sha == ARCHIVE_SHA256) { "the archive $archive has SHA-256 $sha, which differs from its pin" }
            val sources = File(work, "src").apply { mkdirs() }
            ZipFile(zipped).use { zip ->
                for (name in SOURCES + HEADERS) {
                    val entry = checkNotNull(zip.getEntry("checker/src/$name")) { "checker/src/$name is not in the archive" }
                    zip.getInputStream(entry).use { input -> File(sources, name).outputStream().use { input.copyTo(it) } }
                }
            }
            val patch = File(work, PATCH)
            checkNotNull(Solchecker::class.java.getResourceAsStream(PATCH)) { "the patch $PATCH is not packaged" }
                .use { input -> patch.outputStream().use { input.copyTo(it) } }
            check(sha256(patch) == PATCH_SHA256) { "the patch $PATCH differs from its pin" }
            val patched = ToolProcess.run(listOf(patcher, "-p1", "-d", sources.path, "-i", patch.path), work, BUILD_TIMEOUT_MS)
            check(patched.exit == 0) { "$patcher could not apply $PATCH (exit ${patched.exit}): ${patched.tail()}" }
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

    companion object {
        /** The upstream release the sources come from. */
        const val UPSTREAM_VERSION = "1.0.4"
        /** The binary's identity, the upstream release and the lab's patch, which also names its directory so a binary
         *  built otherwise is never reused. */
        const val VERSION = "$UPSTREAM_VERSION+eignex-lab-longnum-1"
        /** The lab's patch to the upstream sources, a resource beside this class. */
        const val PATCH = "solchecker-1.0.4-longnum.patch"
        const val PATCH_SHA256 = "fea62f2aa48bb13729e72e40c918b8dd11d8e5c440ae8cf093570ba5220a37be"
        /** The checker version each verdict records: the binary's identity and the patch it was built with. */
        const val CHECKER_VERSION = "$VERSION (patch sha256 $PATCH_SHA256)"
        /** The upstream archive, byte for byte as Zuse Institute Berlin published it: a resource beside this class, with
         *  its MIT license beside it as `miplib2017-testscript-LICENSE`. */
        const val ARCHIVE = "miplib2017-testscript-v$UPSTREAM_VERSION.zip"
        const val ARCHIVE_SHA256 = "37b21a7f1ee968aed60675fb070c9286eccc6a953c8bc50120c9246b052b47b9"
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
 * its README still says 1e-4). Each value is written as its exact plain decimal, which the patched solchecker reads up
 * to [MAX_NUMBER] characters, so a value with no exact decimal (a fraction like 1/3) or only a longer one leaves the
 * claim unsupported rather than checked against a rounding of it; so does a SOL line over [MAX_LINE] characters. A
 * file solchecker cannot read, or a run it aborts, is unsupported in its own words.
 */
class MpsChecker(private val solchecker: () -> File, private val timeoutMs: Long = TIMEOUT_MS) : FormatChecker {
    override val format = "MPS"
    override val policy = Policies.MPS
    override val checker = "MIPLIB 2017 solchecker"

    override fun check(case: CheckCase, work: File): Verdict {
        val binary = solchecker()
        val sha = sha256(binary)
        fun verdict(state: String, reason: String) = Verdict(state, reason, Solchecker.CHECKER_VERSION, sha)
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
        /** The longest number the patched solchecker reads (upstream's was 31): it asserts every one is shorter than
         *  4096 characters. */
        const val MAX_NUMBER = 4095
        /** The longest SOL line the patched solchecker reads whole, without its newline (upstream's was 1022). */
        const val MAX_LINE = 8190

        /** The SOL file of witness `v name=value …` with objective [objective]; throws, saying why, on what solchecker
         *  cannot read exactly. */
        fun solution(witness: String, objective: String): String = buildString {
            val tokens = witness.trim().split(Regex("\\s+"))
            require(tokens.firstOrNull() == "v") { "the witness is not a `v name=value` line" }
            fun line(text: String) {
                require(text.length <= MAX_LINE) {
                    "the SOL line for ${text.substringBefore(' ').take(64)} is over $MAX_LINE characters, solchecker's input limit"
                }
                appendLine(text)
            }
            line("=obj= ${decimal(objective)}")
            val seen = HashSet<String>()
            for (token in tokens.drop(1)) {
                val at = token.lastIndexOf('=')
                require(at > 0 && at < token.length - 1) { "malformed coordinate $token" }
                val name = token.substring(0, at)
                require(seen.add(name)) { "variable $name assigned twice" }
                line("$name ${decimal(token.substring(at + 1))}")
            }
        }

        /** [text], a decimal or a fraction, as the exact plain decimal solchecker reads, with no exponent for it to
         *  expand. */
        fun decimal(text: String): String {
            require(NUMBER.matches(text)) { "value $text is not a number" }
            val value = if ('/' in text) {
                val (p, q) = text.split('/').map(::BigInteger)
                require(q.signum() > 0) { "value $text has no positive denominator" }
                runCatching { BigDecimal(p).divide(BigDecimal(q)) }
                    .getOrElse { throw IllegalArgumentException("value $text has no exact decimal, and solchecker reads decimals only") }
            } else BigDecimal(text)
            val digits = value.stripTrailingZeros()
            val tooLong = { "value ${text.take(64)}${if (text.length > 64) "…" else ""} has no exact decimal of at most $MAX_NUMBER " +
                "characters, solchecker's input limit" }
            // A scale this far out means more digits than the limit, so a huge exponent is never written out.
            require(digits.scale() in -MAX_NUMBER..MAX_NUMBER, tooLong)
            val plain = (if (digits.scale() < 0) digits.setScale(0) else digits).toPlainString()
            require(plain.length <= MAX_NUMBER, tooLong)
            return plain
        }
    }
}
