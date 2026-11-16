package com.eignex.lab.util

/**
 * Keeping the lab's own work off the cores the solves run on. On macOS a background process is confined to the
 * efficiency cores, which leaves the performance cores to the cases; elsewhere it gets the lowest priority. Only work
 * that runs beside cases goes here, never the runner itself: the cases it starts would inherit the policy.
 */
object Background {
    private val mac = System.getProperty("os.name").orEmpty().startsWith("Mac")

    /** The prefix that runs a command in the background. */
    val prefix: List<String> = if (mac) listOf("taskpolicy", "-b") else listOf("nice", "-n", "19")

    /** Put this process in the background, as the API does: a page reading every case of a run competes with the
     *  solves otherwise. */
    fun self() {
        val pid = ProcessHandle.current().pid().toString()
        val cmd = if (mac) listOf("taskpolicy", "-b", "-p", pid) else listOf("renice", "-n", "19", "-p", pid)
        runCatching { ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor() }
            .onFailure { println("could not move to the background: ${it.message}") }
    }
}
