package com.eignex.lab.cli

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.web.*
import com.eignex.lab.worker.*

import java.nio.file.Path
import kotlin.io.path.readText

/** Render an experimental batch session without starting the lab, opening its database, or contacting a cluster. */
internal object NomadSpecCli {
    fun main(args: List<String>): Int {
        if (args.size != 1) {
            System.err.println("usage: eignex-lab nomad-spec <session.json>")
            return 2
        }
        return try {
            val spec = NomadBatchJob.json.decodeFromString<NomadBatchJob>(Path.of(args.single()).readText())
            println(NomadBatchJob.json.encodeToString(spec.render()))
            0
        } catch (e: Exception) {
            System.err.println("cannot render Nomad session: ${e.message}")
            2
        }
    }
}
