package com.eignex.lab

import java.nio.file.Path

/** Source repositories the lab may build. Repository input is a GitHub slug, never a URL or a local path. */
object Repositories {
    const val DEFAULT = "Eignex/klause"
    const val KOBLAS = "Eignex/koblas"
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

    fun source(config: Config, repository: String): String {
        val url = url(repository)
        if (repository == DEFAULT) require(config.repoUrl in setOf(url, url.removeSuffix(".git"))) {
            "LAB_REPO must be https://github.com/$DEFAULT.git; select another Eignex repository in the job's repository field"
        }
        return url
    }

    fun mirror(config: Config, repository: String): Path {
        requireAllowed(repository)
        return if (repository == DEFAULT) config.mirror else config.dataDir.resolve("repos").resolve(repository.substringAfter('/').lowercase() + ".git")
    }
}
