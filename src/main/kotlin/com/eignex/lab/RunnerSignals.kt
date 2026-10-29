package com.eignex.lab

/**
 * What the API asks of the runner, another process, as files under `LAB_DATA` it looks for between jobs: an update
 * ([Config.updateRequest]) and a Docker cleanup ([Config.dockerCleanupRequest]). Each request is the time it was made.
 */
class RunnerSignals(private val config: Config) {
    /** Ask the runner to fetch and, when the lab's upstream moved, update between the running job's cases. */
    fun requestUpdate() = config.updateRequest.toFile().writeText("${System.currentTimeMillis()}\n")

    /** Ask the runner to clean Docker up ([DockerCleanup]) once it is between jobs; false when this lab runs no Docker. */
    fun requestDockerCleanup(): Boolean {
        if (!config.requireDocker) return false
        config.dockerCleanupRequest.toFile().writeText("${System.currentTimeMillis()}\n")
        return true
    }
}
