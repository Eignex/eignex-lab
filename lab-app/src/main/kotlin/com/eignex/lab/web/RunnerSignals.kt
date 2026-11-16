package com.eignex.lab.web

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.worker.*

/**
 * What the API asks of the runner, another process, as files under `LAB_DATA` it looks for between jobs: an update
 * ([DataPaths.updateRequest]) and a Docker cleanup ([DataPaths.dockerCleanupRequest]). Each request is the time it was made.
 */
class RunnerSignals(private val paths: DataPaths, private val docker: DockerPolicy) {
    /** Ask the runner to fetch and, when the lab's upstream moved, update between the running job's cases. */
    fun requestUpdate() = paths.updateRequest.toFile().writeText("${System.currentTimeMillis()}\n")

    /** Ask the runner to clean Docker up ([DockerCleanup]) once it is between jobs; false when this lab runs no Docker. */
    fun requestDockerCleanup(): Boolean {
        if (!docker.required) return false
        paths.dockerCleanupRequest.toFile().writeText("${System.currentTimeMillis()}\n")
        return true
    }
}
