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

/**
 * HotSpot's compilation log (`-XX:+LogCompilation`), read as an XML stream: its `tty` section's queued tasks, installed
 * code and code made not entrant, and each compiler thread's own log, which HotSpot merges in when the JVM exits, with
 * every task's calls and whether each was inlined, at what depth. A log cut short reads up to where it stops.
 */
internal object HotSpotLog {
    /**
     * HotSpot writes a task's `level` only when it is below the highest tier, C2's: a `task` or `task_queued` without
     * one is a C2 task.
     */
    const val HIGHEST_LEVEL = 4

    class Task(
        val id: Int,
        val method: String,
        val osr: Boolean,
        var level: Int?,
        val start: Double?,
        val osrBci: Int? = null,
        /** The root's bytecode size. */
        val bytes: Long? = null,
    ) {
        var done: Double? = null
        var success: Boolean? = null
        var failure: String? = null
        var nmsize: Long? = null
        var inlinedBytes: Long? = null
        val inlined = HashMap<String, Int>()
        val inlineFailures = HashMap<String, Int>()
        /** Every inlining decision, in the order the compiler made it. */
        val inlining = ArrayList<InlineDecision>()

        val durationMs: Double? get() = done?.let { end -> start?.let { (end - it) * 1000.0 } }
    }

    /** A `task_queued`: a compile id HotSpot handed a compiler, which may never have taken it up. */
    class Queued(val id: Int, val method: String, val osr: Boolean, val osrBci: Int?, val level: Int?, val stamp: Double?, val bytes: Long?)

    /** An `nmethod`: code a compilation installed. */
    data class NMethod(val compiler: String?, val level: Int?, val osr: Boolean = false, val method: String? = null, val stamp: Double? = null)

    class Capture(
        val tasks: Map<Int, Task>,
        /** Each compile id's installed code, from the log's `nmethod` entries; native wrappers are in [wrappers]. */
        val nmethods: Map<Int, NMethod>,
        val threads: Int,
        val inlineSuccess: Int,
        val inlineFail: Int,
        val inlineFailures: Map<String, Int>,
        /** Why parsing stopped before the end, null when it read to the end. */
        val error: String?,
        /** Whether the log has HotSpot's end mark, `hotspot_log_done`. */
        val done: Boolean,
        val bytes: Long,
        val queued: Map<Int, Queued> = emptyMap(),
        /** Why each compile id's code was first made not entrant. */
        val notEntrant: Map<Int, String> = emptyMap(),
        /** Compile ids of native and intrinsic wrappers (a `compile_kind` other than `osr`, such as `c2n`), which compile nothing. */
        val wrappers: Set<Int> = emptySet(),
    )

    /** Read the log at [path], gunzipping it on the way when its name ends in `.gz`; [Capture.bytes] is the file's size. */
    fun read(path: Path): Capture = Files.newInputStream(path).use { raw ->
        val input = if (path.name.endsWith(".gz")) java.util.zip.GZIPInputStream(raw, 1 shl 16) else raw.buffered(1 shl 16)
        read(input, Files.size(path))
    }

    private class MethodEntry(val holder: String, val name: String, val returns: String?, val arguments: String?, val bytes: Long?)

    /** A method being parsed into the task, the root or an inlined callee, and the bytecode it is at. */
    private class Frame(val method: String) {
        var bci: Int? = null
    }

    fun read(input: java.io.InputStream, bytes: Long): Capture {
        val tasks = LinkedHashMap<Int, Task>()
        val queued = HashMap<Int, Queued>()
        val nmethods = HashMap<Int, NMethod>()
        val notEntrant = HashMap<Int, String>()
        val wrappers = HashSet<Int>()
        val failures = HashMap<String, Int>()
        var threads = 0
        var successes = 0
        var fails = 0
        var done = false
        var error: String? = null
        // The ids a task's klass, type and method entries define, valid within that task.
        val klasses = HashMap<String, String>()
        val types = HashMap<String, String>()
        val methods = HashMap<String, MethodEntry>()
        val names = HashMap<String, String>()
        var task: Task? = null
        var call: String? = null
        var callBci: Int? = null
        var callBytes: Long? = null
        val frames = ArrayDeque<Frame>()
        // C2's late inlining of a call it first left out: the callee, the call's chain of sites (`jvms`, innermost
        // first), and the depth the callee's parse then starts below.
        var late: String? = null
        var lateBytes: Long? = null
        val lateChain = ArrayList<Pair<String, Int?>>()
        var lateBase = 0
        val factory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }
        var reader: XMLStreamReader? = null
        try {
            reader = factory.createXMLStreamReader(input)
            fun name(id: String): String = names.getOrPut(id) { methods[id]?.let { describe(it, klasses, types) } ?: "method#$id" }
            while (reader.hasNext()) {
                val type = reader.next()
                if (type == XMLStreamConstants.END_ELEMENT) {
                    when (reader.localName) {
                        "task" -> task = null
                        "parse" -> if (frames.isNotEmpty()) {
                            frames.removeLast()
                            if (frames.isEmpty()) lateBase = 0
                        }
                        "late_inline" -> {
                            val callee = late
                            val site = lateChain.firstOrNull()
                            if (callee != null && site != null) {
                                task?.inlining?.add(InlineDecision(lateChain.size, site.first, callee, site.second, true, "late inline",
                                    lateBytes, late = true))
                            }
                            lateBase = lateChain.size
                            late = null
                        }
                    }
                    continue
                }
                if (type != XMLStreamConstants.START_ELEMENT) continue
                fun attr(name: String): String? = reader.getAttributeValue(null, name)
                when (reader.localName) {
                    "compilation_log" -> threads++
                    "task_queued" -> attr("compile_id")?.toIntOrNull()?.let { id ->
                        queued[id] = Queued(id, method(attr("method")), attr("compile_kind") == "osr", attr("osr_bci")?.toIntOrNull(),
                            attr("level")?.toIntOrNull() ?: HIGHEST_LEVEL, attr("stamp")?.toDoubleOrNull(), attr("bytes")?.toLongOrNull())
                    }
                    "nmethod" -> attr("compile_id")?.toIntOrNull()?.let { id ->
                        val kind = attr("compile_kind")
                        if (kind != null && kind != "osr") wrappers += id
                        else nmethods[id] = NMethod(attr("compiler"), attr("level")?.toIntOrNull(), attr("compile_kind") == "osr",
                            attr("method")?.let(::method), attr("stamp")?.toDoubleOrNull())
                    }
                    "make_not_entrant" -> attr("compile_id")?.toIntOrNull()?.let { id -> notEntrant.putIfAbsent(id, attr("reason") ?: "unknown") }
                    "task" -> {
                        val id = attr("compile_id")?.toIntOrNull() ?: continue
                        klasses.clear(); types.clear(); methods.clear(); names.clear(); frames.clear()
                        call = null; late = null; lateChain.clear(); lateBase = 0
                        task = Task(id, method(attr("method")), attr("compile_kind") == "osr", attr("level")?.toIntOrNull() ?: HIGHEST_LEVEL,
                            attr("stamp")?.toDoubleOrNull(), attr("osr_bci")?.toIntOrNull(), attr("bytes")?.toLongOrNull()).also { tasks[id] = it }
                    }
                    "klass" -> attr("id")?.let { id -> klasses[id] = attr("name").orEmpty() }
                    "type" -> attr("id")?.let { id -> types[id] = attr("name").orEmpty() }
                    "method" -> attr("id")?.let { id ->
                        methods[id] = MethodEntry(attr("holder").orEmpty(), attr("name").orEmpty(), attr("return"), attr("arguments"),
                            attr("bytes")?.toLongOrNull())
                        names.remove(id)
                    }
                    "parse" -> frames.addLast(Frame(attr("method")?.let(::name) ?: task?.method ?: "unknown"))
                    "bc" -> frames.lastOrNull()?.bci = attr("bci")?.toIntOrNull()
                    "call" -> {
                        val id = attr("method")
                        call = id?.let(::name)
                        callBci = frames.lastOrNull()?.bci
                        callBytes = id?.let { methods[it]?.bytes }
                    }
                    "late_inline" -> {
                        val id = attr("method")
                        late = id?.let(::name) ?: "unknown"
                        lateBytes = id?.let { methods[it]?.bytes }
                        lateChain.clear()
                    }
                    "jvms" -> if (late != null) lateChain += (attr("method")?.let(::name) ?: "unknown") to attr("bci")?.toIntOrNull()
                    "inline_success", "inline_fail" -> {
                        val inlined = reader.localName == "inline_success"
                        val reason = attr("reason").orEmpty()
                        if (inlined) {
                            successes++
                            task?.inlined?.merge(call ?: "unknown", 1, Int::plus)
                        } else {
                            fails++
                            failures.merge(reason, 1, Int::plus)
                            task?.inlineFailures?.merge(reason, 1, Int::plus)
                        }
                        task?.let {
                            it.inlining += InlineDecision(lateBase + frames.size, frames.lastOrNull()?.method ?: it.method, call ?: "unknown",
                                callBci, inlined, reason, callBytes)
                        }
                    }
                    "failure" -> task?.let { it.failure = it.failure ?: attr("reason") }
                    "task_done" -> task?.let {
                        it.success = attr("success")?.let { s -> s == "1" }
                        it.done = attr("stamp")?.toDoubleOrNull()
                        it.nmsize = attr("nmsize")?.toLongOrNull()
                        it.inlinedBytes = attr("inlined_bytes")?.toLongOrNull()
                    }
                    "hotspot_log_done" -> done = true
                }
            }
        } catch (e: Exception) {
            val line = reader?.location?.lineNumber?.takeIf { it > 0 }
            error = (if (line != null) "line $line: " else "") + (e.message?.lineSequence()?.lastOrNull { it.isNotBlank() } ?: e::class.simpleName)
        } finally {
            runCatching { reader?.close() }
        }
        return Capture(tasks, nmethods, threads, successes, fails, failures, error, done, bytes, queued, notEntrant, wrappers)
    }

    /**
     * A task's `method` attribute, `holder name descriptor`, as JFR names methods: `holder.name(descriptor)`. A hidden
     * class's holder, which the log writes `Name/0x…`, JFR writes `Name.0x…`.
     */
    internal fun method(attribute: String?): String {
        val parts = attribute.orEmpty().split(' ')
        return if (parts.size >= 3) "${holder(parts[0])}.${parts[1]}${parts[2]}" else attribute ?: "unknown"
    }

    private fun holder(name: String): String = name.replace('/', '.')

    /** A `method` entry as `holder.name(descriptor)`, its descriptor rebuilt from the task's type and klass entries. */
    private fun describe(method: MethodEntry, klasses: Map<String, String>, types: Map<String, String>): String {
        fun descriptor(id: String): String {
            types[id]?.let { name -> PRIMITIVES[name]?.let { return it } }
            val klass = klasses[id] ?: return "?"
            return if (klass.startsWith("[")) klass.replace('.', '/') else "L${klass.replace('.', '/')};"
        }
        val arguments = method.arguments.orEmpty().split(' ').filter { it.isNotEmpty() }.joinToString("") { descriptor(it) }
        val returns = method.returns?.let { descriptor(it) } ?: "?"
        return "${klasses[method.holder]?.let(::holder) ?: "?"}.${method.name}($arguments)$returns"
    }

    private val PRIMITIVES = mapOf(
        "void" to "V", "boolean" to "Z", "byte" to "B", "char" to "C", "short" to "S", "int" to "I", "long" to "J",
        "float" to "F", "double" to "D",
    )
}
