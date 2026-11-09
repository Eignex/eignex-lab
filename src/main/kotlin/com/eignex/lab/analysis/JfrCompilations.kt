package com.eignex.lab.analysis

import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

import java.io.OutputStream
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedMethod
import jdk.jfr.consumer.RecordingFile
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A recording's compilations and what says how far it can be trusted, read with the JDK's own JFR consumer. */
internal object JfrCompilations {
    class Compilation(
        val id: Int,
        val method: String,
        val compiler: String?,
        val level: Int?,
        val osr: Boolean,
        val succeeded: Boolean,
        val ms: Double,
        val codeBytes: Long?,
        val inlinedBytes: Long?,
        /** When it began, in epoch ms. */
        val startMs: Long? = null,
    )

    class Capture(
        val compilations: List<Compilation>,
        /** Each failed compile id's failure message. */
        val failures: Map<Int, String>,
        val statistics: JvmCompilerStatistics?,
        val jvm: JvmProvenance?,
        /** `jdk.Compilation`'s threshold setting, as the recording's settings events held it; null when they did not. */
        val threshold: String?,
        val compilationEnabled: Boolean?,
        /** The JVM's shutdown reason, null when the recording has no shutdown event. */
        val shutdown: String?,
        val dataLossBytes: Long,
        val events: Long,
        /** Why reading stopped before the end, null when it read to the end. */
        val error: String?,
    )

    fun read(path: Path): Capture {
        val compilations = ArrayList<Compilation>()
        val failures = HashMap<Int, String>()
        val properties = HashMap<String, String>()
        var statistics: Pair<java.time.Instant, JvmCompilerStatistics>? = null
        var information: RecordedEvent? = null
        var threshold: String? = null
        var enabled: Boolean? = null
        var shutdown: String? = null
        var dataLoss = 0L
        var events = 0L
        var error: String? = null
        try {
            RecordingFile(path).use { file ->
                val compilationType = runCatching { file.readEventTypes().firstOrNull { it.name == "jdk.Compilation" }?.id }.getOrNull()
                while (file.hasMoreEvents()) {
                    val event = file.readEvent()
                    events++
                    when (event.eventType.name) {
                        "jdk.Compilation" -> compilations += compilation(event)
                        "jdk.CompilationFailure" -> failures[event.getInt("compileId")] = event.getString("failureMessage").orEmpty()
                        "jdk.CompilerStatistics" -> {
                            val at = event.startTime
                            if (statistics == null || !at.isBefore(statistics.first)) statistics = at to statistics(event)
                        }
                        "jdk.JVMInformation" -> information = event
                        "jdk.InitialSystemProperty" -> properties[event.getString("key").orEmpty()] = event.getString("value").orEmpty()
                        "jdk.ActiveSetting" -> if (compilationType != null && event.getLong("id") == compilationType) {
                            when (event.getString("name")) {
                                "threshold" -> threshold = event.getString("value")
                                "enabled" -> enabled = event.getString("value")?.toBooleanStrictOrNull()
                            }
                        }
                        "jdk.Shutdown" -> shutdown = event.getString("reason").orEmpty()
                        "jdk.DataLoss" -> dataLoss += event.getLong("amount")
                    }
                }
            }
        } catch (e: Exception) {
            error = e.message ?: e::class.simpleName
        }
        val jvm = information?.let { event ->
            JvmProvenance(
                name = field(event, "jvmName"), version = field(event, "jvmVersion"),
                vendor = properties["java.vm.vendor"] ?: properties["java.vendor"], javaVersion = properties["java.vm.version"] ?: properties["java.version"],
                jvmArguments = field(event, "jvmArguments"), jvmFlags = field(event, "jvmFlags"),
                javaArguments = field(event, "javaArguments"), pid = runCatching { event.getLong("pid") }.getOrNull(),
            )
        }
        return Capture(compilations, failures, statistics?.second, jvm, threshold, enabled, shutdown, dataLoss, events, error)
    }

    private fun compilation(event: RecordedEvent): Compilation {
        val method = event.getValue<RecordedMethod?>("method")
        return Compilation(
            id = event.getInt("compileId"),
            method = method?.let { "${it.type.name}.${it.name}${it.descriptor}" } ?: "unknown",
            compiler = field(event, "compiler"),
            level = if (event.hasField("compileLevel")) event.getInt("compileLevel") else null,
            osr = event.hasField("isOsr") && event.getBoolean("isOsr"),
            succeeded = !event.hasField("succeded") || event.getBoolean("succeded"),
            ms = event.duration.toNanos() / 1e6,
            codeBytes = if (event.hasField("codeSize")) event.getLong("codeSize") else null,
            inlinedBytes = if (event.hasField("inlinedBytes")) event.getLong("inlinedBytes") else null,
            startMs = runCatching { event.startTime.toEpochMilli() }.getOrNull(),
        )
    }

    private fun statistics(event: RecordedEvent): JvmCompilerStatistics {
        fun int(name: String) = if (event.hasField(name)) event.getInt(name) else null
        fun ms(name: String) = if (event.hasField(name)) event.getDuration(name).toNanos() / 1e6 else null
        return JvmCompilerStatistics(int("compileCount") ?: 0, int("bailoutCount"), int("invalidatedCount"), int("osrCompileCount"),
            int("standardCompileCount"), ms("totalTimeSpent"), ms("peakTimeSpent"))
    }

    private fun field(event: RecordedEvent, name: String): String? = if (event.hasField(name)) event.getString(name) else null
}

