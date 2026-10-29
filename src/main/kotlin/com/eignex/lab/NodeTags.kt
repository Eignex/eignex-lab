package com.eignex.lab

import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit

/** Optional placement requirements. No selector means the existing placement policy applies. */
@Serializable
data class NodeSelector(val require: Set<String> = emptySet(), val exclude: Set<String> = emptySet())

@Serializable
data class NodeObservation(val host: String, val tags: Set<String>, val cpu: CpuCapabilities? = null)

/** OS-visible CPU facts, kept separately from operator labels. */
@Serializable
data class CpuCapabilities(val arch: String = "unknown", val features: Set<String> = emptySet(), val model: String? = null, val detected: Boolean = false) {
    val tags: Set<String> get() = buildSet {
        add("arch:$arch")
        features.forEach { add("simd:$it") }
        if ("avx512f" in features) add("simd:avx512")
        if ("asimd" in features) add("simd:neon")
        if (!detected) add("hardware:unknown")
    }
}

object NodeTags {
    private val TAG = Regex("[a-z0-9][a-z0-9:._/-]*")
    /** The public selector syntax: ordinary labels are required; !label excludes a node capability. */
    fun selector(tags: List<String>): NodeSelector = NodeSelector(
        tags.filterNot { it.startsWith('!') }.toSet(),
        tags.filter { it.startsWith('!') }.map { it.drop(1) }.toSet(),
    ).also(::validate)

    fun labels(selector: NodeSelector?): List<String> =
        (selector?.require.orEmpty() + selector?.exclude.orEmpty().map { "!$it" }).sorted()

    val probeScript: String = requireNotNull(NodeTags::class.java.getResource("/node-capabilities.sh")).readText()
    /** One CPU architecture and its primary SIMD profile; compatibility tags retain the detected capabilities. */
    fun hardwareProfile(tags: Set<String>): String {
        val architecture = tags.singleOrNull { it.startsWith("arch:") }?.substringAfter(':')
        val profiles = when (architecture) {
            "x86_64" -> listOf("avx512" to "AVX-512", "avx512f" to "AVX-512", "avx2" to "AVX2", "avx" to "AVX", "sse2" to "SSE2")
            "arm64" -> listOf("sme2" to "SME2", "sme" to "SME", "sve2" to "SVE2", "sve" to "SVE", "neon" to "NEON", "asimd" to "NEON")
            else -> emptyList()
        }
        val simd = profiles.firstOrNull { "simd:${it.first}" in tags }?.second
        val cpu = when (architecture) { "arm64" -> "ARM64"; "x86_64" -> "x86_64"; else -> "Unknown" }
        return listOfNotNull(cpu, simd).joinToString(" / ")
    }
    val localCapabilities: CpuCapabilities by lazy {
        runCatching {
            val process = ProcessBuilder("bash", "-c", probeScript).redirectErrorStream(true).start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                CpuCapabilities()
            } else if (process.exitValue() == 0) parse(process.inputStream.bufferedReader().readText()) else CpuCapabilities()
        }.getOrDefault(CpuCapabilities())
    }

    fun validate(selector: NodeSelector?) {
        if (selector == null) return
        require((selector.require + selector.exclude).all { TAG.matches(it) }) { "node tags must be lowercase labels without spaces" }
        require(selector.require.intersect(selector.exclude).isEmpty()) { "a node tag cannot be both required and excluded" }
        require(!("spot" in selector.require && "on-demand" in selector.require)) { "a node cannot be both spot and on-demand" }
        require(selector.require.count { it.startsWith("arch:") } <= 1) { "a node can have only one architecture" }
    }

    fun matches(selector: NodeSelector?, tags: Set<String>): Boolean {
        if (selector == null) return true
        if ("hardware:unknown" in tags && (selector.require + selector.exclude).any { it.startsWith("arch:") || it.startsWith("simd:") }) return false
        return tags.containsAll(selector.require) && selector.exclude.none { it in tags }
    }

    private fun reserved(tag: String) = tag in setOf("local", "cloud", "spot", "on-demand", "hardware:unknown") ||
        tag.startsWith("arch:") || tag.startsWith("simd:") || tag.startsWith("instance:")

    fun observed(capabilities: CpuCapabilities, labels: Set<String>, cloud: Boolean = false, spot: Boolean = false, type: String? = null): Set<String> =
        labels.filterNot(::reserved).toSet() + capabilities.tags + if (cloud)
            setOf("cloud", if (spot) "spot" else "on-demand") + listOfNotNull(type?.let { "instance:$it" })
        else setOf("local")

    fun spotOnly(selector: NodeSelector?): Boolean = selector != null && ("spot" in selector.require || "on-demand" in selector.exclude)
    /** The market a job with [selector] buys cloud capacity on: on-demand when its tags ask for it, else Spot. */
    fun market(selector: NodeSelector?): CapacityMarket = if (onDemand(false, selector)) CapacityMarket.ON_DEMAND else CapacityMarket.SPOT

    fun onDemand(profile: Boolean, selector: NodeSelector?): Boolean =
        selector?.let { "on-demand" in it.require || "spot" in it.exclude } == true || profile && !spotOnly(selector)

    fun parse(output: String): CpuCapabilities {
        val values = output.lineSequence().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val arch = when (values["arch"]?.lowercase()) { "x86_64", "amd64" -> "x86_64"; "aarch64", "arm64" -> "arm64"; else -> "unknown" }
        val features = values["features"].orEmpty().lowercase().split(Regex("\\s+")).filter { TAG.matches(it) }.toSortedSet()
        return CpuCapabilities(arch, features, values["model"]?.takeIf { it.isNotBlank() }, values["detected"] == "1" && arch != "unknown")
    }
}
