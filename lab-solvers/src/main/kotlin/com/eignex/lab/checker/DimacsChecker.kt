package com.eignex.lab.checker

import java.io.File
import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Complete original-CNF assignment checking in a bounded child. */
class DimacsChecker : FormatChecker {
    override val format = "DIMACS"
    override val policy = "lab-dimacs-independent-complete-point-v2"
    override val checker = "eignex-lab independent original CNF point evaluator"
    private val version = DimacsSourceEvaluation.VERSION

    override fun check(case: CheckCase, work: File): Verdict {
        fun verdict(state: String, reason: String) = Verdict(state, reason, version, jarSha)
        val expected = (case.record["sourceHashes"] as? JsonObject)?.get("model") as? JsonPrimitive
        if (expected == null || !expected.content.matches(Regex("[a-f0-9]{64}")))
            return verdict(LabCheck.NOT_RUN, "no original model SHA-256 was retained")
        if (case.optimize || case.claimedObjective != null)
            return verdict(LabCheck.UNSUPPORTED, "DIMACS adapter checks SAT points only; no objective or optimality coverage")
        if (case.sources.data != null) return verdict(LabCheck.UNSUPPORTED, "$format sources cannot have a separate data file")
        val compressed = case.sources.model.name.endsWith(".zst")
        val model = File(work, "original.source${if (compressed) ".zst" else ""}")
        try {
            case.sources.model.inputStream().use { model.writeBytes(bounded(it)) }
        } catch (e: InputLimit) { return verdict(LabCheck.UNSUPPORTED, e.message.orEmpty()) }
        if (!compressed && sha256(model) != expected.content) return verdict(LabCheck.NOT_RUN, "original source snapshot differs from recorded SHA-256")
        val request = buildJsonObject {
            put("witness", case.witness)
            put("sourceSha256", expected.content)
        }.toString()
        if (request.toByteArray().size > DimacsSourceEvaluation.MAX_BYTES)
            return verdict(LabCheck.UNSUPPORTED, "point witness request exceeds 32 MiB")
        val file = File(work, "dimacs-request.json").apply { writeText(request) }
        val java = File(System.getProperty("java.home"), "bin/java")
        val classpath = System.getProperty("java.class.path").split(File.pathSeparator).map { File(it).absoluteFile }
        val run = ToolProcess.run(listOf(java.path, "-Xms16m", "-Xmx256m", "-XX:+UseSerialGC",
            "-XX:MaxMetaspaceSize=128m", "-XX:ReservedCodeCacheSize=64m", "-XX:CompressedClassSpaceSize=32m",
            "-XX:ActiveProcessorCount=1", "-cp", classpath.joinToString(File.pathSeparator) { it.path },
            DimacsSourceCheckMain::class.java.name, model.absolutePath, file.absolutePath), work, TIMEOUT_MS,
            limits = ToolLimits(cpuSeconds = 10, timeoutMs = TIMEOUT_MS),
            env = mapOf("JAVA_TOOL_OPTIONS" to "", "JDK_JAVA_OPTIONS" to "", "_JAVA_OPTIONS" to ""), inputFiles = listOf(model, file, java) + classpath.filter { it.isFile })
        val receipts = listOfNotNull(run.resources)
        val inputs = listOf(CheckedInput(file.canonicalPath, sha256(file), request))
        fun done(state: String, reason: String) = verdict(state, reason).copy(resourceReceipts = receipts, inputs = inputs,
            diagnostic = run.stdout.take(DIAGNOSTIC_CAP))
        if (run.exit != 0 || run.resources?.qualified() != true)
            return done(LabCheck.NOT_RUN, "point checker did not complete within its resource envelope (exit ${run.exit}, timeout ${run.timedOut}): ${run.tail()}")
        val result = runCatching { Json.decodeFromString(DimacsEvaluation.serializer(), run.stdout.trim()) }.getOrNull()
            ?: return done(LabCheck.NOT_RUN, "point checker returned no complete verdict")
        if (result.state !in LabCheck.STATES) return done(LabCheck.NOT_RUN, "point checker returned an unknown state")
        return done(result.state, result.reason)
    }

    companion object {
        const val TIMEOUT_MS = 15_000L
        internal val jarSha: String? by lazy {
            File(DimacsSourceCheckMain::class.java.protectionDomain.codeSource.location.toURI()).takeIf { it.isFile }?.let(::sha256)
        }
        internal class InputLimit : RuntimeException("point source exceeds 32 MiB")
        internal fun bounded(input: InputStream): ByteArray = input.readNBytes(DimacsSourceEvaluation.MAX_BYTES + 1).also {
            if (it.size > DimacsSourceEvaluation.MAX_BYTES) throw InputLimit()
        }
    }
}

/** All CNF parsing and evaluation run in the bounded child, including UTF-8 decoding. */
object DimacsSourceCheckMain {
    @JvmStatic fun main(args: Array<String>) {
        val request = Json.parseToJsonElement(File(args[1]).readText()) as JsonObject
        fun field(key: String) = (request[key] as? JsonPrimitive)?.content
        val result = try {
            val file = File(args[0])
            val bytes = CorpusStreams.open(file, windowLog = 26).use { DimacsChecker.bounded(it) }
            if (sha256(bytes) != field("sourceSha256")) DimacsEvaluation(LabCheck.NOT_RUN, "original source snapshot differs from recorded SHA-256")
            else DimacsSourceEvaluation.check(bytes.decodeToString(throwOnInvalidSequence = true), requireNotNull(field("witness")))
        } catch (e: DimacsChecker.Companion.InputLimit) {
            DimacsEvaluation(LabCheck.UNSUPPORTED, e.message.orEmpty())
        } catch (e: java.nio.charset.CharacterCodingException) {
            DimacsEvaluation(LabCheck.UNSUPPORTED, "source is not UTF-8")
        }
        println(Json.encodeToString(DimacsEvaluation.serializer(), result))
    }
}
