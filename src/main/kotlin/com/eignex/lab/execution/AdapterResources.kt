package com.eignex.lab.execution

import com.eignex.lab.util.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

internal object AdapterResources {
    const val POLICY = "lab-adapter-resources-v1"
    const val SCOPE = "adapter-run-preflight-and-waited-descendants"
    const val TIMER = "/usr/bin/time"
    const val RAW = "adapter-resources.txt"
    const val RECEIPT = "adapter-resources.json"
    const val LIMIT = 4096
    private val sha = Regex("[0-9a-f]{64}")
    private val decimal = Regex("[0-9]{1,12}[.,][0-9]{2}")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Serializable
    data class Values(val userSeconds: String, val systemSeconds: String, val wallSeconds: String,
                      val maxProcessRssKiB: Long, val exit: Int)

    @Serializable
    data class Receipt(val policy: String = POLICY, val scope: String = SCOPE, val state: String,
                       val reason: String?, val timerSha256: String?, val commandSha256: String,
                       val processExit: Int, val interrupted: Boolean, val rawSha256: String?, val resources: Values?)

    fun command(invocation: String, index: Int): String {
        require(index >= 0)
        val format = "userSeconds=%U\\nsystemSeconds=%S\\nwallSeconds=%e\\nmaxProcessRssKiB=%M\\nexit=%x"
        return "exec $TIMER --quiet --format=${quote(format)} --output=\"\$JOB_DIR/cases/$index/$RAW\" " +
            "bash -c ${quote(invocation)}"
    }

    fun values(text: String): Values {
        require(text.length <= LIMIT)
        val lines = text.lineSequence().filter { it.isNotEmpty() }.map { line ->
            val parts = line.split('=', limit = 2)
            require(parts.size == 2)
            parts[0] to parts[1]
        }.toList()
        require(lines.size == 5 && lines.map { it.first }.toSet() == setOf(
            "userSeconds", "systemSeconds", "wallSeconds", "maxProcessRssKiB", "exit"))
        val fields = lines.toMap()
        fun seconds(name: String): String = fields.getValue(name).also { require(decimal.matches(it)) }.replace(',', '.')
        val rss = fields.getValue("maxProcessRssKiB").toLong().also { require(it >= 0) }
        val exit = fields.getValue("exit").toInt().also { require(it in 0..255) }
        return Values(seconds("userSeconds"), seconds("systemSeconds"), seconds("wallSeconds"), rss, exit)
    }

    fun capture(directory: Path, index: Int, command: String, exit: Int, timerSha256: String?) {
        val root = directory.resolve("cases/$index")
        Files.createDirectories(root)
        val raw = root.resolve(RAW)
        val text = runCatching { Files.newInputStream(raw).use { input ->
            val bytes = input.readNBytes(LIMIT + 1)
            require(bytes.size <= LIMIT)
            bytes.toString(Charsets.UTF_8)
        } }.getOrNull()
        val resources = text?.let { runCatching { values(it) }.getOrNull() }
        val interrupted = Files.exists(directory.resolve("$index.termination"))
        val reason = when {
            timerSha256?.matches(sha) != true -> "timer identity unavailable"
            interrupted -> "adapter execution was interrupted"
            resources == null -> "timer output missing or malformed"
            resources.exit != exit -> "timer and supervised exits disagree"
            else -> null
        }
        val receipt = Receipt(state = if (reason == null) "complete" else "unavailable", reason = reason,
            timerSha256 = timerSha256, commandSha256 = digest(command), processExit = exit,
            interrupted = interrupted, rawSha256 = text?.let(::digest), resources = resources)
        Files.writeString(root.resolve(RECEIPT), json.encodeToString(receipt))
    }

    fun project(record: String?, requested: Boolean, metadata: String?, receiptSha256: String?,
                raw: String?, rawSha256: String?, command: String?): String? {
        val fields = record?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return record
        val clean = fields - "labAdapterResources"
        if (!requested) return JsonObject(clean).toString()
        val receipt = metadata?.takeIf { it.length <= LIMIT * 2 }?.let { runCatching { json.decodeFromString<Receipt>(it) }.getOrNull() }
        val verified = receipt?.takeIf {
            it.policy == POLICY && it.scope == SCOPE && receiptSha256?.matches(sha) == true && digest(metadata!!) == receiptSha256 &&
                it.timerSha256?.matches(sha) == true && command != null && it.commandSha256 == digest(command) &&
                raw != null && rawSha256?.matches(sha) == true && digest(raw) == rawSha256 && it.rawSha256 == rawSha256 &&
                runCatching { values(raw) == it.resources }.getOrDefault(false) &&
                it.resources?.exit == it.processExit && !it.interrupted && it.state == "complete" && it.reason == null
        }
        val details = buildJsonObject {
            put("policy", POLICY); put("scope", SCOPE)
            put("state", if (verified != null) "complete" else "unavailable")
            put("reason", if (verified == null) receipt?.reason ?: "accepted adapter measurement unavailable" else null)
            receiptSha256?.let { put("receiptSha256", it) }
            if (receipt != null) put("receipt", json.encodeToJsonElement(receipt))
        }
        return JsonObject(clean + ("labAdapterResources" to details)).toString()
    }

    private fun digest(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
