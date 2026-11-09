package com.eignex.lab.domain

/** What a committed entry point may name: checked when an experiment is accepted and again before it runs. */
object ProfileRules {
    private val MAIN = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
    private val TASK = Regex(":?[A-Za-z0-9_][A-Za-z0-9_-]*(?::[A-Za-z0-9_][A-Za-z0-9_-]*)*")
    private val OPTION = Regex("--?[A-Za-z][A-Za-z0-9-]*")
    private val PATH = Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")

    internal fun relative(path: String): Boolean = PATH.matches(path) && path.split('/').none { it == "." || it == ".." }

    fun validate(repository: String, profile: ProfileMain) {
        Repositories.requireAllowed(repository)
        require((profile.main.isNotEmpty()) != (profile.script != null)) { "profile must name exactly one of main or script" }
        require(profile.main.isEmpty() || MAIN.matches(profile.main)) { "profile.main must be a fully qualified JVM class" }
        profile.script?.let { require(relative(it)) { "profile.script must be a repository-relative committed executable" } }
        require(profile.buildTasks.all { TASK.matches(it) }) { "profile.buildTasks must contain Gradle task paths, not options or commands" }
        require(profile.classpath.all { relative(it.removeSuffix("/*")) }) { "profile.classpath must contain repository-relative paths or jar directories" }
        require(profile.script != null || profile.classpath.isNotEmpty()) {
            "profile.classpath is required for every JVM main"
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
}
