package com.eignex.lab

/** Shell helpers shared by the remote builder and the cache failure integration tests. */
internal object AwsBuildCache {
    val functions: String = requireNotNull(AwsBuildCache::class.java.getResource("/aws-build-cache.sh")).readText()
    const val BUILDS = "builds/verified-v1"
    const val GRADLE = "gradle/verified-v1/home.tar.zst"
    fun worktree(path: String, cores: Int, archive: String?, region: String): List<String> {
        val build = "(cd ${quote(path)} && ./gradlew :klause-cli:installJvmDist :klause-bench:installDist -q --max-workers=$cores)"
        val validate = "validate_lab_cache ${quote(path)} install"
        if (archive == null) return listOf(build, validate)
        return listOf(
            "if restore_lab_cache ${quote(archive)} ${quote(path)} install ${quote(region)}; then",
            "  echo 'reused a validated cached build'",
            "else",
            "  rm -rf ${quote("$path/klause-cli/build/install")} ${quote("$path/klause-bench/build/install")}",
            "  $build",
            "  $validate",
            "  ${Experiments.captureProvenance(path)}",
            "  validate_lab_provenance ${quote(path)}",
            "  built=1",
            "  publish_lab_cache ${quote(archive)} ${quote(path)} ${quote(region)} klause-cli/build/install klause-bench/build/install",
            "fi",
            validate,
        )
    }
}
