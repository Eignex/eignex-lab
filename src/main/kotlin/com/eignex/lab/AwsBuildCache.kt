package com.eignex.lab

/** Shell helpers shared by the remote builder and the cache failure integration tests. */
internal object AwsBuildCache {
    val functions: String = requireNotNull(AwsBuildCache::class.java.getResource("/aws-build-cache.sh")).readText()
    /**
     * Where a commit's validated installs are kept, by commit. v2 builds package the MIPLIB solchecker
     * ([SourceValidation.MPS_CHECKER_BUILD]); a v1 archive of a commit since that became opt-in has none, so none is
     * reused.
     */
    const val BUILDS = "builds/verified-v2"
    const val GRADLE = "gradle/verified-v1/home.tar.zst"
    fun worktree(path: String, cores: Int, archive: String?, region: String): List<String> {
        val build = "(cd ${quote(path)} && ./gradlew ${klauseBuildTasks(referenceOnly = false)} -q --max-workers=$cores)"
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
