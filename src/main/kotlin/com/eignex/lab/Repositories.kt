package com.eignex.lab

import java.nio.file.Path

/** Source repositories the lab may build. Repository input is a GitHub slug, never a URL or a local path. */
object Repositories {
    const val DEFAULT = "Eignex/eignex-lab"
    private val SLUG = Regex("Eignex/[A-Za-z0-9_.-]+")

    fun requireAllowed(repository: String) {
        require(SLUG.matches(repository) && repository.substringAfter('/') !in setOf(".", "..") && !repository.endsWith(".git")) {
            "repository must name an Eignex repository as Eignex/<name>, not '$repository'"
        }
    }

    fun url(repository: String): String {
        requireAllowed(repository)
        return "https://github.com/$repository.git"
    }

    fun source(repository: String): String = url(repository)

    /** The lab's mirror of [repository], under [paths]. */
    fun mirror(paths: DataPaths, repository: String): Path {
        requireAllowed(repository)
        return paths.repositories.resolve(repository.substringAfter('/').lowercase() + ".git")
    }
}
