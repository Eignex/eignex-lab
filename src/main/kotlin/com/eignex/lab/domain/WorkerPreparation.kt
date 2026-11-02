package com.eignex.lab.domain

import kotlinx.serialization.Serializable

/** The declared output platform of a repository-owned cross build, independent of its preparation host. */
@Serializable
data class WorkerBuildTarget(val os: String, val arch: String) {
    init { require(os in setOf("linux", "mac") && arch in setOf("amd64", "arm64")) }
    val tags get() = setOf("platform:$os", "arch:${if (arch == "amd64") "x86_64" else arch}")
    fun matches(bundle: WorkerBundle) = os == bundle.os && arch == bundle.arch
    fun validateRequirements(tags: Set<String>) {
        require(tags.filter { it.startsWith("platform:") || it.startsWith("arch:") }.all { it in this.tags }) {
            "declared preparation target conflicts with execution platform requirements"
        }
    }
}

/** Explicit opt-in: compilation/planning host capabilities and the output platform the build promises to produce. */
@Serializable
data class WorkerPreparation(val requiredTags: Set<String>, val target: WorkerBuildTarget) {
    init {
        NodeTags.validate(NodeSelector(require = requiredTags))
        require(requiredTags.none { it in PLACEMENT }) { "preparation capabilities cannot override placement or purchasing policy" }
        require(requiredTags.filter { it.startsWith("platform:") }.toSet() == setOf("platform:${target.os}")) {
            "preparation host and target must declare the same operating system"
        }
    }
    fun selector(execution: NodeSelector?): NodeSelector = capabilities(execution, requiredTags)

    companion object {
        private val PLACEMENT = setOf("local", "cloud", "spot", "on-demand")
        private fun hardware(tag: String) = tag.startsWith("arch:") || tag.startsWith("simd:") ||
            tag.startsWith("platform:") || tag.startsWith("instance:") || tag == "hardware:unknown"
        /** Candidate discovery may inspect other host hardware; only an explicit committed contract can admit it. */
        fun capabilities(execution: NodeSelector?, required: Set<String> = emptySet()): NodeSelector = NodeSelector(
            execution?.require.orEmpty().filterNot(::hardware).toSet() + required,
            execution?.exclude.orEmpty().filterNot(::hardware).toSet(),
        ).also(NodeTags::validate)
    }
}
