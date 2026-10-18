package com.eignex.lab

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class PlacementTest {
    private val spec = ExperimentSpec("e", listOf(mapOf("suite" to "s")), description = "d")

    private fun config(aws: Boolean = true): Config {
        val dir = Files.createTempDirectory("lab")
        if (aws) {
            dir.resolve("aws").toFile().mkdirs()
            dir.resolve("aws/aws.properties").toFile().writeText("region=eu-north-1\nkeyName=k\nkeyFile=/k.pem\nsecurityGroup=sg-1\nvcpuQuota=32\n")
        }
        return Config(dataDir = dir, cores = 8, maxParallel = 6)
    }

    @Test
    fun `scheduled runs, confirmations, reference runs, problem lists and bounded profiles stay on the Mac`() {
        val reference = spec.copy(base = mapOf("backend" to REFERENCE_BACKEND, "solver" to "highs"))
        val bounded = ExperimentSpec("p", profile = ProfileMain("com.eignex.klause.bench.profile.T", runTimeoutSec = 60))

        assertEquals(listOf(true, true, true, true, true, false), listOf(
            Placement.macOnly(spec, "status-sweep@abc123"),
            Placement.macOnly(spec, "e${Confirm.SUFFIX}"),
            Placement.macOnly(reference, "r"),
            Placement.macOnly(spec.copy(problemList = listOf(Problem("s", "p"))), "e"),
            Placement.macOnly(bounded, "p"),
            Placement.macOnly(spec, "e"),
        ))
    }

    @Test
    fun `every other job may go to either, unless only one of them can run it`() {
        assertEquals(Experiments.AUTO_HOST, Placement.host(spec, "e", macFits = true, awsFits = true))
        assertEquals(Experiments.AWS_HOST, Placement.host(spec.copy(profileCli = true), "e", macFits = true, awsFits = true))
        assertEquals(Experiments.AWS_HOST, Placement.host(spec, "e", macFits = false, awsFits = true))
        assertEquals(Experiments.LAB_HOST, Placement.host(spec, "e", macFits = true, awsFits = false))
        assertEquals(Experiments.LAB_HOST, Placement.host(spec, "sweep@abc", macFits = true, awsFits = true))
    }

    @Test
    fun `a submitted host is not followed, and a profile job goes Mac first too`() {
        val config = config()
        val profile = ExperimentSpec("p", profile = ProfileMain("com.eignex.klause.bench.profile.T"))

        assertEquals(Experiments.AUTO_HOST, Placement.host(spec.copy(host = Experiments.LAB_HOST), "e", config))
        assertEquals(Experiments.AUTO_HOST, Placement.host(spec.copy(host = Experiments.AWS_HOST, machines = 3), "e", config))
        assertEquals(Experiments.AUTO_HOST, Placement.host(profile, "p", config))
        assertEquals(Experiments.LAB_HOST, Placement.host(spec, "e", config(aws = false)))
    }

    @Test
    fun `cases too big for the Mac go to AWS, and too big for any node stay on the Mac to be refused there`() {
        val config = config()

        assertEquals(Experiments.AWS_HOST, Placement.host(spec.copy(base = mapOf("processors" to "12")), "e", config))
        assertEquals(Experiments.AWS_HOST, Placement.host(spec.copy(parallel = 8), "e", config))
        assertEquals(Experiments.LAB_HOST, Placement.host(spec.copy(base = mapOf("processors" to "20")), "e", config))
    }

    @Test
    fun `queued jobs that have not been planned are placed again by the rules, planned ones keep their host`() {
        val config = config()
        val store = Store(config.database)
        val chosen = store.create("a", "main", emptyList(), experiment = spec.copy(name = "a", host = Experiments.AWS_HOST, machines = 2))
        val mac = store.create("b", "main", emptyList(), experiment = spec.copy(name = "b", host = Experiments.LAB_HOST))
        val sweep = store.create("s@abc", "main", emptyList(), experiment = spec.copy(name = "s@abc", host = Experiments.LAB_HOST))
        val planned = store.create("c", "main", listOf("true" to 10L), experiment = spec.copy(name = "c", host = Experiments.AWS_HOST))

        assertEquals(listOf(chosen, mac), Placement.replaceQueued(store, config))
        assertEquals(listOf(Experiments.AUTO_HOST, Experiments.AUTO_HOST, Experiments.LAB_HOST, Experiments.AWS_HOST),
            listOf(chosen, mac, sweep, planned).map { store.job(it)!!.experiment!!.host })
        assertEquals(null, store.job(chosen)!!.experiment!!.machines)
    }
}
