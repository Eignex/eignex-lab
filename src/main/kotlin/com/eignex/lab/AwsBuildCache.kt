package com.eignex.lab

/** Shell helpers shared by the remote builder and the cache failure integration tests. */
internal object AwsBuildCache {
    val functions: String = requireNotNull(AwsBuildCache::class.java.getResource("/aws-build-cache.sh")).readText()
    /**
     * Where a commit's validated installs that package the MIPLIB solchecker were kept, by commit, while the bench
     * checked MPS witnesses itself. Such an install still serves: the checker it packages is never run.
     */
    const val BUILDS = "builds/verified-v2"
    /** Where a commit's validated installs without the MIPLIB solchecker are kept, by commit: every build now. */
    const val PLAIN_BUILDS = "builds/verified-v2-plain"
    const val GRADLE = "gradle/verified-v1/home.tar.zst"

    /**
     * The archives in [bucket] a build of [sha] may be restored from, in the order they are tried; the build publishes
     * to the last, the plain one. An install from when builds packaged the MIPLIB solchecker serves as well.
     */
    fun archives(bucket: String, sha: String, arch: String = "x86_64"): List<String> =
        if (arch == "x86_64") listOf("s3://$bucket/$BUILDS/$sha.tar.zst", "s3://$bucket/$PLAIN_BUILDS/$sha.tar.zst")
        else if (arch == "arm64") listOf("s3://$bucket/$PLAIN_BUILDS/$arch/$sha.tar.zst") else emptyList()

    /**
     * The steps that put [path]'s build in place: restored from the first of [archives] that validates, else built and
     * published to the last of them. With no [archives] it is always built.
     */
    fun worktree(path: String, cores: Int, archives: List<String>, region: String): List<String> {
        val build = "(cd ${quote(path)} && ./gradlew ${klauseBuildTasks(referenceOnly = false)} -q --max-workers=$cores)"
        val validate = "validate_lab_cache ${quote(path)} install"
        if (archives.isEmpty()) return listOf(build, validate)
        val restore = archives.joinToString(" || ") { "restore_lab_cache ${quote(it)} ${quote(path)} install ${quote(region)}" }
        return listOf(
            "if $restore; then",
            "  echo 'reused a validated cached build'",
            "else",
            "  rm -rf ${quote("$path/klause-cli/build/install")} ${quote("$path/klause-bench/build/install")}",
            "  $build",
            "  $validate",
            "  ${Experiments.captureProvenance(path)}",
            "  validate_lab_provenance ${quote(path)}",
            "  built=1",
            "  publish_lab_cache ${quote(archives.last())} ${quote(path)} ${quote(region)} klause-cli/build/install klause-bench/build/install",
            "fi",
            validate,
        )
    }

    /** What a node's set of built commits holds for [sha] with or without the CLI measurement launcher. */
    fun builtKey(sha: String, profileCli: Boolean): String = "$sha:$profileCli"

    /** Whether a node that built what [built] holds has [sha] ready for a job with [profileCli]. */
    fun hasBuilt(built: Set<String>, sha: String, profileCli: Boolean): Boolean = builtKey(sha, profileCli) in built

    /** [built] after [sha] was built again for [profileCli]: a rebuild replaces whatever the worktree held before, so
     *  its other entries go. */
    fun rebuilt(built: MutableSet<String>, sha: String, profileCli: Boolean) = synchronized(built) {
        built.removeAll { it.substringBefore(':') == sha }
        built += builtKey(sha, profileCli)
    }
}
