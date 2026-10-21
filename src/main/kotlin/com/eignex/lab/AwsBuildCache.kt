package com.eignex.lab

/** Shell helpers shared by the remote builder and the cache failure integration tests. */
internal object AwsBuildCache {
    val functions: String = requireNotNull(AwsBuildCache::class.java.getResource("/aws-build-cache.sh")).readText()
    /**
     * Where a commit's validated installs that package the MIPLIB solchecker ([SourceValidation.MPS_CHECKER_BUILD]) are
     * kept, by commit. A v1 archive of a commit since packaging became opt-in has none, so none is reused.
     */
    const val BUILDS = "builds/verified-v2"
    /** Where a commit's validated installs without the MIPLIB solchecker are kept, by commit. */
    const val PLAIN_BUILDS = "builds/verified-v2-plain"
    const val GRADLE = "gradle/verified-v1/home.tar.zst"

    /**
     * The archives in [bucket] a build of [sha] may be restored from, in the order they are tried; the build publishes
     * to the last. A build with the checker serves a job that needs none, but one without it never serves a job that
     * does ([SourceValidation.needsMpsChecker]).
     */
    fun archives(bucket: String, sha: String, mpsChecker: Boolean): List<String> {
        val checked = "s3://$bucket/$BUILDS/$sha.tar.zst"
        return if (mpsChecker) listOf(checked) else listOf(checked, "s3://$bucket/$PLAIN_BUILDS/$sha.tar.zst")
    }

    /**
     * The steps that put [path]'s build in place: restored from the first of [archives] that validates, else built,
     * with the MIPLIB solchecker when [mpsChecker], and published to the last of them. With no [archives] it is always
     * built.
     */
    fun worktree(path: String, cores: Int, archives: List<String>, region: String, mpsChecker: Boolean): List<String> {
        val build = "(cd ${quote(path)} && ./gradlew ${klauseBuildTasks(referenceOnly = false, mpsChecker)} -q --max-workers=$cores)"
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

    /** What a node's set of built commits holds for [sha] built with or without a profiling launcher and the checker. */
    fun builtKey(sha: String, profileCli: Boolean, mpsChecker: Boolean): String = "$sha:$profileCli:$mpsChecker"

    /**
     * Whether a node that built what [built] holds has [sha] ready for a job with [profileCli] that needs the checker
     * when [mpsChecker]: built for it with the checker, or, when it needs none, without.
     */
    fun hasBuilt(built: Set<String>, sha: String, profileCli: Boolean, mpsChecker: Boolean): Boolean =
        builtKey(sha, profileCli, true) in built || !mpsChecker && builtKey(sha, profileCli, false) in built

    /** [built] after [sha] was built again for [profileCli] with the checker or without ([mpsChecker]): a rebuild replaces
     *  whatever the worktree held before, so its other entries go. */
    fun rebuilt(built: MutableSet<String>, sha: String, profileCli: Boolean, mpsChecker: Boolean) = synchronized(built) {
        built.removeAll { it.substringBefore(':') == sha }
        built += builtKey(sha, profileCli, mpsChecker)
    }
}
