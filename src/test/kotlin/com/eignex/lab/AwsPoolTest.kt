package com.eignex.lab

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The node pool across lab restarts and updates, against a fake AWS CLI and fake SSH. */
class AwsPoolTest {
    private val now = System.currentTimeMillis()

    /** The cloud as the fake CLI and SSH see it: the lab instances running, which do not answer SSH, and what ran. */
    private class Cloud {
        val running: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
        val unreachable: MutableSet<String> = Collections.synchronizedSet(HashSet())
        val terminated: MutableList<String> = Collections.synchronizedList(ArrayList())
        val launches: MutableList<String> = Collections.synchronizedList(ArrayList())
        val scripts = ConcurrentHashMap<String, MutableList<String>>()

        fun scripts(instance: String): List<String> = scripts[instance]?.let { synchronized(it) { it.toList() } }.orEmpty()

        fun cli(aws: AwsConfig) = object : AwsCli(aws) {
            override fun admitThisMachine() {}
            override fun call(vararg args: String): String {
                val a = args.toList()
                return when {
                    "run-instances" in a -> "i-new".also { launches += it; running += it }
                    "terminate-instances" in a -> {
                        val ids = a.drop(a.indexOf("--instance-ids") + 1).takeWhile { !it.startsWith("--") }
                        terminated += ids
                        running -= ids.toSet()
                        ids.joinToString(" ")
                    }
                    "describe-instances" in a && "--filters" in a -> running.toList().joinToString(" ")
                    "describe-instances" in a && a.last().contains("PublicIpAddress") -> "10.0.0.1"
                    "describe-instances" in a -> if (a[a.indexOf("--instance-ids") + 1] in running) "running\tNone" else "terminated\tNone"
                    "get-parameter" in a -> "ami-1"
                    else -> ""
                }
            }
        }

        fun ssh(instance: String, ip: String, aws: AwsConfig, known: Path, cancelled: () -> Boolean) =
            object : Ssh(instance, ip, aws, known, cancelled) {
                override fun start(script: String, mergeErrors: Boolean): Process {
                    scripts.computeIfAbsent(instance) { Collections.synchronizedList(ArrayList()) } += script
                    val (out, code) = when {
                        instance in unreachable -> "ssh: connect to host $ip port 22: Connection refused" to 255
                        "kill -KILL" in script && "eignex-lab-step" in script -> "ready" to 0
                        else -> "" to 0
                    }
                    return ProcessBuilder("bash", "-c", "printf '%s\\n' \"\$OUT\"; exit $code")
                        .apply { environment()["OUT"] = out }.redirectErrorStream(true).start()
                }
            }
    }

    private fun lab(settings: String = ""): Config {
        val config = Config(dataDir = Files.createTempDirectory("lab"), cores = 4)
        config.dataDir.resolve("aws").toFile().mkdirs()
        config.dataDir.resolve("aws/aws.properties").toFile()
            .writeText("region=eu-north-1\nkeyName=k\nkeyFile=/k.pem\nsecurityGroup=sg-1\nnodeIdleMin=60\n$settings")
        return config
    }

    private fun savePool(config: Config, vararg nodes: SavedNode) =
        config.dataDir.resolve("aws/${AwsWorker.POOL_FILE}").toFile()
            .writeText(Json.encodeToString(PoolState.serializer(), PoolState(launched = 3, nodes = nodes.toList())))

    private fun readPool(config: Config): PoolState =
        Json.decodeFromString(PoolState.serializer(), config.dataDir.resolve("aws/${AwsWorker.POOL_FILE}").toFile().readText())

    private fun saved(instance: String, launchedAt: Long = now - 2 * 3_600_000L) =
        SavedNode(instance, "c7i.2xlarge", 8, AwsWorker.SPOT, "10.0.0.9", launchedAt = launchedAt, built = listOf("sha1"))

    private fun worker(config: Config, cloud: Cloud, runner: Runner = Runner(config, Store(config.database), startCloud = { null })) =
        AwsWorker(runner, Store(config.database), config, config.aws!!, cliFor = cloud::cli, sshFor = cloud::ssh,
            pollMs = 50, sweepMs = 300, heartbeatMs = 100, nodePollMs = 50)

    private fun until(what: String, timeoutMs: Long = 10_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    /** A self-updater whose upstream is always ahead, so an update waits, and whose restart is recorded. */
    private fun dueUpdater(config: Config, restarted: () -> Unit = {}) = SelfUpdater(
        config.dataDir, 0, config.dataDir.resolve("update.log").toFile(),
        exec = { _, cmd, _ ->
            when {
                "HEAD" in cmd -> 0 to "aaaa"
                "@{u}" in cmd -> 0 to "bbbb"
                else -> 0 to ""
            }
        },
        restart = restarted,
    )

    @Test
    fun `a restart adopts known running nodes, cleaned first, and terminates unknown, unreachable and stateless ones`() {
        val config = lab()
        val cloud = Cloud()
        cloud.running += listOf("i-known", "i-dead", "i-stranger")
        cloud.unreachable += "i-dead"
        val launchedAt = now - 2 * 3_600_000L
        savePool(config, saved("i-known", launchedAt), saved("i-dead"), saved("i-gone"))

        val worker = worker(config, cloud)
        worker.recover()

        assertEquals(setOf("i-dead", "i-stranger"), cloud.terminated.toSet())
        assertEquals(setOf("i-known"), cloud.running.toSet())
        // Every process the previous runner left on it is killed and its job files removed before anything else.
        val first = cloud.scripts("i-known").first()
        assertTrue("kill -KILL" in first && "rm -rf ~/jobs" in first, first)
        // Its first launch stands, so its maxHours does, and the pool keeps it for the next restart.
        val pool = readPool(config)
        assertEquals(listOf("i-known"), pool.nodes.map { it.instance })
        assertEquals(launchedAt, pool.nodes.single().launchedAt)
        assertEquals(listOf("sha1"), pool.nodes.single().built)
        assertTrue(worker.poolRecovered)

        // No state file: nothing is adopted, every lab instance goes.
        val bare = lab()
        val other = Cloud().apply { running += listOf("i-a", "i-b") }
        worker(bare, other).recover()
        assertEquals(setOf("i-a", "i-b"), other.terminated.toSet())

        // An unreadable one fails closed too.
        val garbled = lab()
        garbled.dataDir.resolve("aws/${AwsWorker.POOL_FILE}").toFile().writeText("{not json")
        val third = Cloud().apply { running += "i-a" }
        worker(garbled, third).recover()
        assertEquals(listOf("i-a"), third.terminated.toList())
    }

    @Test
    fun `an adopted node with nothing to take retires after nodeIdleMin`() {
        val config = lab("nodeIdleMin=0\n")
        val cloud = Cloud().apply { running += "i-known" }
        savePool(config, saved("i-known"))
        val runner = Runner(config, Store(config.database), startCloud = { null })
        runner.recover()

        worker(config, cloud, runner).recover()

        until("the idle node's termination") { "i-known" in cloud.terminated }
        until("the pool file to drop it") { readPool(config).nodes.isEmpty() }
    }

    @Test
    fun `the sweep keeps terminating unregistered instances, the heartbeat reaches nodes, and the pages see adoption`() {
        val config = lab()
        val cloud = Cloud().apply { running += "i-known" }
        val launchedAt = now - 3_600_000L
        savePool(config, saved("i-known", launchedAt))
        val worker = worker(config, cloud)
        thread(isDaemon = true) { worker.loop() }

        until("adoption") { worker.poolRecovered }
        cloud.running += "i-late"
        until("the periodic sweep") { "i-late" in cloud.terminated }
        assertFalse("i-known" in cloud.terminated)
        until("a heartbeat") { cloud.scripts("i-known").any { AwsWorker.TOUCH_HEARTBEAT in it && "kill -KILL" !in it } }
        until("the pool report") { readPoolReport(config)?.nodes?.isNotEmpty() == true }
        val node = readPoolReport(config)!!.nodes.single()
        assertEquals(listOf<Any?>("i-known", true, launchedAt), listOf(node.instance, node.adopted, node.launchedAt))
    }

    @Test
    fun `a node powers itself off when the lab stops touching its heartbeat`() {
        val aws = lab().aws!!
        val script = AwsWorker.bootstrap(aws)

        assertTrue("eignex-lab-watchdog.timer" in script && "-gt 1800" in script && "shutdown -h now" in script, script)
        assertTrue("shutdown -h +1440" in script)
        assertFalse("watchdog" in AwsWorker.bootstrap(aws.copy(nodeWatchdogMin = 0)))
        for (shell in listOf(script, AwsWorker.ADOPT_SCRIPT)) {
            val check = ProcessBuilder("bash", "-n", "-c", shell).redirectErrorStream(true).start()
            val out = check.inputStream.bufferedReader().readText()
            assertEquals(0, check.waitFor(), out)
        }
    }

    @Test
    fun `an adopted node takes work once cleaned`() {
        val config = lab()
        val store = Store(config.database)
        val spec = ExperimentSpec("aws", listOf(mapOf("suite" to "s")), host = Experiments.AWS_HOST)
        val runner = Runner(config, store, startCloud = { null }).apply { recover() }
        val id = store.create("aws", "main", emptyList(), experiment = spec)
        val cloud = Cloud().apply { running += "i-known" }
        savePool(config, saved("i-known"))

        worker(config, cloud, runner).recover()

        until("the node to take the job") { Files.exists(config.jobDir(id).resolve("job.log")) &&
            "by node i-known" in config.jobDir(id).resolve("job.log").toFile().readText() }
        val first = cloud.scripts("i-known").first()
        assertTrue("kill -KILL" in first, first)
    }

    @Test
    fun `a waiting update neither retires idle nodes nor holds back a launch`() {
        val config = lab("nodeLaunchLocalHours=0\nnodeSustainMin=0\n")
        val store = Store(config.database)
        // Its cases need more cores than the adopted node has, so a launch is the only way it runs.
        val spec = ExperimentSpec("aws", listOf(mapOf("suite" to "s")), grid = mapOf("processors" to listOf("8")), host = Experiments.AWS_HOST)
        val runner = Runner(config, store, startCloud = { null }, selfUpdater = dueUpdater(config)).apply { recover() }
        store.create("aws", "main", emptyList(), experiment = spec)
        assertTrue(runner.updateWaiting())
        val cloud = Cloud().apply { running += "i-known" }
        savePool(config, saved("i-known"))
        val worker = worker(config, cloud, runner)
        thread(isDaemon = true) { worker.loop() }

        until("a launch while the update waits") { cloud.launches.isNotEmpty() }
        Thread.sleep(300)
        assertFalse("i-known" in cloud.terminated)
    }

    @Test
    fun `a lab update runs without waiting for the cloud nodes' work`() {
        val config = lab()
        val store = Store(config.database)
        var restarted = false
        val runner = Runner(config, store, startCloud = { { true } }, selfUpdater = dueUpdater(config) {
            restarted = true
            throw Restarted()
        })

        val loop = thread(isDaemon = true) { runCatching { runner.loop() } }
        loop.join(10_000)

        assertTrue(restarted)
    }

    private class Restarted : RuntimeException("restarted")
}
