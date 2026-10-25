package com.eignex.lab

/** Build and launch committed entry points without assuming a particular repository's module layout. */
object ProfileRuns {
    private val MAIN = Regex("com\\.eignex(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
    private val TASK = Regex(":?[A-Za-z0-9_][A-Za-z0-9_-]*(?::[A-Za-z0-9_][A-Za-z0-9_-]*)*")
    private val OPTION = Regex("--?[A-Za-z][A-Za-z0-9-]*")
    private val PATH = Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")

    private fun relative(path: String): Boolean = PATH.matches(path) && path.split('/').none { it == "." || it == ".." }

    fun validate(repository: String, profile: ProfileMain) {
        Repositories.requireAllowed(repository)
        require((profile.main.isNotEmpty()) != (profile.script != null)) { "profile must name exactly one of main or script" }
        require(profile.main.isEmpty() || MAIN.matches(profile.main)) { "profile.main must be a JVM class under com.eignex" }
        profile.script?.let { require(relative(it)) { "profile.script must be a repository-relative committed executable" } }
        require(profile.buildTasks.all { TASK.matches(it) }) { "profile.buildTasks must contain Gradle task paths, not options or commands" }
        require(profile.classpath.all { relative(it.removeSuffix("/*")) }) { "profile.classpath must contain repository-relative paths or jar directories" }
        require(profile.script != null || profile.classpath.isNotEmpty() || repository == Repositories.DEFAULT) {
            "profile.classpath is required for a JVM main outside Eignex/klause"
        }
        require(profile.jvmArgs.all { it.startsWith('-') && it.none(Char::isISOControl) }) { "profile.jvmArgs must contain JVM options" }
        require(profile.script == null || profile.classpath.isEmpty() && profile.jvmArgs.isEmpty()) { "a script controls its own classpath and JVM options" }
        require(profile.outputArgument == null || profile.script != null && OPTION.matches(profile.outputArgument)) {
            "profile.outputArgument must be a script option such as --output"
        }
        profile.outputArgument?.let { option ->
            require(profile.args.none { it == option || it.startsWith("$option=") }) { "$option is supplied by the lab to retain the report" }
        }
    }

    fun buildTasks(repository: String, profile: ProfileMain): List<String> {
        validate(repository, profile)
        return profile.buildTasks.ifEmpty {
            if (repository == Repositories.DEFAULT && profile.script == null && profile.classpath.isEmpty())
                listOf(":klause-cli:installJvmDist", ":klause-bench:installDist") else emptyList()
        }
    }

    fun command(worktree: String, repository: String, profile: ProfileMain, index: Int, corpus: String, debug: Boolean): String {
        validate(repository, profile)
        val prefix = "cd ${quote(worktree)} && export OUT=\"\$JOB_DIR/cases/$index/out\" LAB_REPOSITORY=${quote(repository)} LAB_WORKTREE=${quote(worktree)} && mkdir -p \"\$OUT\" && "
        profile.script?.let { script ->
            // A tracked symlink must not turn a repository entry point into an executable elsewhere on the host.
            return prefix + "git ls-files --error-unmatch -- ${quote(script)} >/dev/null && " +
                "root=\"\$(pwd -P)\" && entry=\"\$(perl -MCwd=abs_path -e 'print abs_path(\$ARGV[0]) // exit 2' -- ${quote(script)})\" && " +
                "case \"\$entry\" in \"\$root\"/*) ;; *) exit 2;; esac && " +
                "exec \"\$entry\" " + profile.args.joinToString(" ", transform = ::quote) +
                (profile.outputArgument?.let { " ${quote(it)} \"\$OUT/report\"" } ?: "")
        }
        val classpath = profile.classpath.ifEmpty { listOf("klause-bench/build/install/klause-bench/lib/*") }
            .joinToString(":") { "$worktree/$it" }
        val legacy = repository == Repositories.DEFAULT
        val env = if (legacy) "export KLAUSE_CORPUS=${quote(corpus)} " +
            "KLAUSE_CLI=${quote("$worktree/klause-cli/build/install/klause-cli-jvm/bin/klause-cli")} " +
            "KLAUSE_BENCH=${quote("$worktree/klause-bench/build/install/klause-bench/bin/klause-bench")} " +
            (if (debug) "" else "KLAUSE_DEBUG=false ") + "&& " else ""
        val jvm = listOf("--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector") +
            (if (legacy) listOf("-Dklause.bench.corpusCache=$corpus", "-Dklause.workspace.root=$worktree") else emptyList()) +
            profile.jvmArgs + listOf("-cp", classpath)
        return prefix + env + "exec \"\${JAVA_HOME:+\$JAVA_HOME/bin/}java\" " +
            "\"-XX:StartFlightRecording=filename=\$OUT/profile.jfr,settings=profile,dumponexit=true\" " +
            (jvm + profile.main + profile.args).joinToString(" ", transform = ::quote)
    }
}
