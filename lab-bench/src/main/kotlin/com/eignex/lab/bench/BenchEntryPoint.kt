package com.eignex.lab.bench

import kotlinx.serialization.Serializable

/** Committed build and launch recipe. Workers load this from the same revision as the suite. */
@Serializable
public data class BenchEntryPoint(
    public val main: String,
    public val classpath: List<String>,
    public val buildTasks: List<String>,
    public val jvmArgs: List<String> = emptyList(),
    public val protocolVersion: Int = BenchProtocol.VERSION,
) {
    init {
        BenchProtocol.requireVersion(protocolVersion)
        require(main.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")))
        require(classpath.isNotEmpty())
        classpath.forEach { portableRelative(it.removeSuffix("/*")) }
        require(buildTasks.all { it.matches(Regex(":?[A-Za-z0-9_][A-Za-z0-9_-]*(?::[A-Za-z0-9_][A-Za-z0-9_-]*)*")) })
        require(jvmArgs.all { it.startsWith("-") && it.none(Char::isISOControl) })
    }
}

/** An input's location before and after the lab relocates a prepared suite. */
@Serializable
public enum class BenchInputRoot { SOURCE, CORPUS }

/** A file or directory the planner resolved. The lab inventories and pins its contents before dispatch. */
@Serializable
public data class BenchInput(
    public val path: String,
    public val root: BenchInputRoot = BenchInputRoot.SOURCE,
    public val role: String = "",
    public val sha256: String? = null,
) {
    init {
        portableRelative(path)
        require(sha256 == null || sha256.matches(Regex("[a-f0-9]{64}")))
    }
}

internal fun portableRelative(value: String) {
    require(value.isNotBlank() && '\\' !in value && ':' !in value && value.none(Char::isISOControl) &&
        value.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Expected a portable relative path: $value" }
}
