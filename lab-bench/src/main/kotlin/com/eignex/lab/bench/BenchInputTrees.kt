package com.eignex.lab.bench

import java.security.MessageDigest

/**
 * A directory input declared by the files beneath it, so a planner that selects from a committed corpus index can
 * name an include directory without materializing it.
 *
 * A declared tree is a [BenchInput] for the directory whose `sha256` is [sha256] of its files. Every one of those
 * files is also an input of the same case, with the same root and its own `sha256`. The lab then plans, publishes and
 * fetches the directory as exactly those files. Declare trees only when the lab sets [ENVIRONMENT] to `true`
 * (together with `LAB_DECLARED_INPUTS`); older labs read a checksum on a directory as a file checksum.
 */
public object BenchInputTrees {
    /** Set to `true` for `describe` and `plan` when the lab accepts directories declared by their files. */
    public const val ENVIRONMENT: String = "LAB_DECLARED_TREES"

    /**
     * The tree checksum of [files], a map from each regular file's path relative to the directory to its lowercase
     * hex SHA-256: the SHA-256 of, for each file in ascending order of its path, the UTF-8 path, a zero byte, the
     * checksum and a zero byte. A tree holds at least one file; empty subdirectories are not part of it.
     */
    public fun sha256(files: Map<String, String>): String {
        require(files.isNotEmpty()) { "A declared tree needs at least one file" }
        val digest = MessageDigest.getInstance("SHA-256")
        for ((path, sha) in files.toSortedMap()) {
            portableRelative(path)
            require(sha.matches(SHA256)) { "Expected a lowercase SHA-256 for $path" }
            digest.update(path.toByteArray(Charsets.UTF_8)); digest.update(0)
            digest.update(sha.toByteArray(Charsets.US_ASCII)); digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** [directory] declared by [files]: each must be a declared input below it with the same [root]. */
    public fun declare(directory: String, files: List<BenchInput>, root: BenchInputRoot = BenchInputRoot.CORPUS,
                       role: String = "include"): BenchInput {
        portableRelative(directory)
        val members = files.associate { file ->
            require(file.root == root && file.path.startsWith("$directory/")) { "${file.path} is not below $directory" }
            file.path.removePrefix("$directory/") to requireNotNull(file.sha256) { "${file.path} declares no sha256" }
        }
        require(members.size == files.distinctBy { it.path to it.sha256 }.size) { "A file below $directory declares two checksums" }
        return BenchInput(directory, root, role, sha256(members))
    }

    private val SHA256 = Regex("[a-f0-9]{64}")
}
