package com.eignex.lab.execution

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

/** Build and launch committed entry points without assuming a particular repository's module layout. */
object ProfileRuns {
    /** The options every profiled JVM starts with, as the lab's own JVMs do; a profile's jvmArgs follow and may add to them. */
    val DEFAULT_JVM_ARGS = listOf("--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector")

    fun buildTasks(repository: String, profile: ProfileMain): List<String> {
        ProfileRules.validate(repository, profile)
        return profile.buildTasks
    }

    fun command(worktree: String, repository: String, profile: ProfileMain, index: Int, corpus: String, debug: Boolean): String {
        ProfileRules.validate(repository, profile)
        val prefix = "cd ${quote(worktree)} && export OUT=\"\$JOB_DIR/cases/$index/out\" LAB_REPOSITORY=${quote(repository)} LAB_WORKTREE=${quote(worktree)} LAB_CORPUS=${quote(corpus)} && mkdir -p \"\$OUT\" && "
        return prefix + when (val entry = profile.entry) {
            // A tracked symlink must not turn a repository entry point into an executable elsewhere on the host.
            is ProfileEntry.Script -> "git ls-files --error-unmatch -- ${quote(entry.script)} >/dev/null && " +
                "root=\"\$(pwd -P)\" && entry=\"\$(perl -MCwd=abs_path -e 'print abs_path(\$ARGV[0]) // exit 2' -- ${quote(entry.script)})\" && " +
                "case \"\$entry\" in \"\$root\"/*) ;; *) exit 2;; esac && " +
                "exec \"\$entry\" " + profile.args.joinToString(" ", transform = ::quote) +
                (entry.outputArgument?.let { " ${quote(it)} \"\$OUT/report\"" } ?: "")
            is ProfileEntry.JvmMain -> {
                val jvm = DEFAULT_JVM_ARGS + entry.jvmArgs + listOf("-cp", entry.classpath.joinToString(":") { "$worktree/$it" })
                "exec \"\${JAVA_HOME:+\$JAVA_HOME/bin/}java\" " +
                    "\"-XX:StartFlightRecording=filename=\$OUT/profile.jfr,settings=profile,dumponexit=true\" " +
                    (jvm + entry.main + profile.args).joinToString(" ", transform = ::quote)
            }
        }
    }
}
