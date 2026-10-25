package com.eignex.lab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * Where the lab runs experiments on AWS, read from `$LAB_DATA/aws/aws.properties` (written by `deploy/aws-setup.sh`),
 * so no service needs reinstalling to change it. The CLI's credentials are a named [profile] in `~/.aws/credentials`.
 */
data class AwsConfig(
    val region: String,
    val profile: String,
    val instanceType: String,
    /** The most cloud nodes up at once, launching ones included. */
    val maxInstances: Int,
    /** The account's on-demand vCPU quota for the family: what every running instance together may hold. */
    val vcpuQuota: Int = DEFAULT_VCPU_QUOTA,
    val keyName: String,
    /** The private key of [keyName], on the lab machine. */
    val keyFile: String,
    val securityGroup: String,
    val subnet: String?,
    /** Cores of one instance a job's cases may hold together: its physical cores, as the local machine's are its
     *  P-cores. */
    val cores: Int,
    /** An instance powers itself off after this long, whatever the lab does: a lost controller cannot leave one running. */
    val maxHours: Int,
    /** The MiniZinc bundle an instance installs, the local machine's version so models compile alike. */
    val minizinc: String,
    /** An image to launch instead of the current Ubuntu 24.04 one. */
    val ami: String?,
    /** An S3 bucket holding a copy of the corpus cache: an instance syncs it down while it builds and pushes back what
     *  it had to fetch, so each collection is fetched from its source once. Null: every instance fetches its own. */
    val corpusBucket: String? = null,
    /** The instance profile whose role may read and write [corpusBucket]. */
    val instanceProfile: String? = null,
    /** How long a node launch keeps asking for spot before it buys on demand, when [onDemand] allows. */
    val spotWaitMin: Long = 15,
    /**
     * Whether the lab buys on-demand nodes for work other than profile jobs (`onDemand=true`). Off, every such node is
     * spot and a launch waits for spot capacity instead of buying on demand. A node launched for a profile job is on
     * demand either way, since a profile stays off spot unless its selector explicitly requests it.
     */
    val onDemand: Boolean = false,
    /** How long a node with nothing to take stays up before the lab terminates it. */
    val nodeIdleMin: Long = 5,
    /** Work the local machine could run, queued below this priority, waits for it: it launches no node, and no node
     *  takes it. */
    val nodeMinPriority: Int = 0,
    /** The first node launches once the waiting work would keep the local machine busy this many hours: the pressure,
     *  in core-hours, at or over the local machine's cores times this ([AwsWorker.threshold], [LaunchGate]). Read from
     *  `nodeLaunchLocalHours`, or `nodeLaunchMacHours`, its older name. */
    val nodeLaunchLocalHours: Double = 1.0,
    /** How long the pressure must stay up before a node launches. */
    val nodeSustainMin: Long = 10,
    /** The least time between two launches. */
    val nodeCooldownMin: Long = 5,
    /** The largest node the pool launches, by hand or not, in vCPUs. Jobs eligible for the local machine are capped
     *  to its cores; cloud-only selectors are limited by compatible cloud sizes. */
    val nodeMaxVcpus: Int = 16,
    /** A node powers itself off, and so terminates, once the lab has not touched its heartbeat for this long
     *  ([AwsWorker.heartbeat]): the lab down or unreachable leaves no node billing until its [maxHours]. Set at
     *  launch; 0 turns the watchdog off for nodes launched after. */
    val nodeWatchdogMin: Long = 30,
    val instanceTypes: List<String> = emptyList(),
    val instanceTags: Map<String, Set<String>> = emptyMap(),
    val threadsPerCore: Map<String, Int> = emptyMap(),
    val nodeTags: Set<String> = emptySet(),
    val arm64Ami: String? = null,
    val architectures: Map<String, String> = emptyMap(),
) {
    /** The sizes a node can be, smallest first: [instanceType] and the larger sizes of its family up to
     *  [nodeMaxVcpus], within the quota. */
    val sizes: List<InstanceSize>
        get() = instanceTypes.ifEmpty { listOf(instanceType) }.flatMap { type ->
            val family = type.substringBefore('.')
            val familyTags = nodeTags + instanceTags[family].orEmpty()
            val baseArch = architectures[type] ?: architectures[family] ?: (familyTags + instanceTags[type].orEmpty()).firstOrNull { it.startsWith("arch:") }?.substringAfter(':') ?: "x86_64"
            val smallest = SIZE_VCPUS[type.substringAfter('.')]
            val expanded = if (smallest == null) listOf(type to DEFAULT_SIZE_VCPUS) else
                SIZE_VCPUS.entries.filter { (_, vcpus) -> vcpus in smallest..minOf(vcpuQuota, maxOf(nodeMaxVcpus, smallest)) }
                    .map { (size, vcpus) -> "$family.$size" to vcpus }
            expanded.map { (actual, vcpus) ->
                val tags = familyTags + instanceTags[actual].orEmpty()
                NodeTags.validate(NodeSelector(require = tags))
                val arch = architectures[actual] ?: architectures[family] ?: tags.firstOrNull { it.startsWith("arch:") }?.substringAfter(':') ?: baseArch
                require(arch in setOf("x86_64", "arm64")) { "cloud architecture must be x86_64 or arm64" }
                val threads = threadsPerCore[actual] ?: threadsPerCore[family] ?: if (arch == "arm64") 1 else 2
                require(threads in 1..2) { "threadsPerCore.$family must be 1 or 2" }
                InstanceSize(actual, vcpus, arch, tags, threads)
            }
        }.distinctBy { it.type }.sortedBy { it.vcpus }

    fun sizeFor(cores: Int, selector: NodeSelector? = null, profile: Boolean = false): InstanceSize? =
        sizes.firstOrNull { it.cores >= cores && NodeTags.matches(selector, it.tags(NodeTags.onDemand(profile, selector))) }

    companion object {
        private const val DEFAULT_VCPU_QUOTA = 32
        private const val DEFAULT_SIZE_VCPUS = 8
        /** vCPUs of the compute families' sizes; [threadsPerCore] determines physical cores. */
        private val SIZE_VCPUS = linkedMapOf("xlarge" to 4, "2xlarge" to 8, "4xlarge" to 16, "8xlarge" to 32, "12xlarge" to 48, "16xlarge" to 64)

        fun load(file: Path): AwsConfig? {
            if (!file.exists()) return null
            val p = Properties().apply { file.toFile().inputStream().use(::load) }
            fun get(key: String, default: String? = null) = p.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } ?: default
            return AwsConfig(
                region = requireNotNull(get("region")) { "aws.properties needs region" },
                profile = get("profile", "eignex-lab")!!,
                instanceType = get("instanceType", "c7i.2xlarge")!!,
                maxInstances = get("maxInstances", "4")!!.toInt(),
                vcpuQuota = get("vcpuQuota", "$DEFAULT_VCPU_QUOTA")!!.toInt(),
                keyName = requireNotNull(get("keyName")) { "aws.properties needs keyName" },
                keyFile = requireNotNull(get("keyFile")) { "aws.properties needs keyFile" },
                securityGroup = requireNotNull(get("securityGroup")) { "aws.properties needs securityGroup" },
                subnet = get("subnet"),
                cores = get("cores", "4")!!.toInt(),
                maxHours = get("maxHours", "24")!!.toInt(),
                minizinc = get("minizinc", "2.9.7")!!,
                ami = get("ami"),
                corpusBucket = get("corpusBucket"),
                instanceProfile = get("instanceProfile"),
                spotWaitMin = get("spotWaitMin", "15")!!.toLong(),
                onDemand = get("onDemand", "false")!!.toBoolean(),
                nodeIdleMin = get("nodeIdleMin", "5")!!.toLong(),
                nodeMinPriority = get("nodeMinPriority", "0")!!.toInt(),
                // `nodeLaunchMacHours`, its older name, is still read.
                nodeLaunchLocalHours = (get("nodeLaunchLocalHours") ?: get("nodeLaunchMacHours", "1.0"))!!.toDouble(),
                nodeSustainMin = get("nodeSustainMin", "10")!!.toLong(),
                nodeCooldownMin = get("nodeCooldownMin", "5")!!.toLong(),
                nodeMaxVcpus = get("nodeMaxVcpus", "16")!!.toInt(),
                nodeWatchdogMin = get("nodeWatchdogMin", "30")!!.toLong(),
                instanceTypes = get("instanceTypes").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                instanceTags = p.stringPropertyNames().filter { it.startsWith("tags.") }.associate {
                    it.removePrefix("tags.") to p.getProperty(it).split(',').map(String::trim).filter(String::isNotEmpty).toSet()
                },
                threadsPerCore = p.stringPropertyNames().filter { it.startsWith("threadsPerCore.") }.associate {
                    it.removePrefix("threadsPerCore.") to p.getProperty(it).trim().toInt()
                },
                nodeTags = get("nodeTags").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).toSet(),
                arm64Ami = get("arm64Ami"),
                architectures = p.stringPropertyNames().filter { it.startsWith("architecture.") }.associate {
                    it.removePrefix("architecture.") to p.getProperty(it).trim()
                },
            )
        }
    }
}

/** An instance size and topology: x86 defaults to two threads per core, ARM to one. */
data class InstanceSize(val type: String, val vcpus: Int, val arch: String = "x86_64", val labels: Set<String> = emptySet(), val threads: Int = 2) {
    val cores: Int get() = vcpus / threads
    fun tags(onDemand: Boolean): Set<String> = labels.filterNot { it in setOf("local", "cloud", "spot", "on-demand") || it.startsWith("arch:") || it.startsWith("instance:") }.toSet() +
        setOf("cloud", "arch:$arch", "instance:$type", if (onDemand) "on-demand" else "spot")
}

/** The AWS CLI, as the lab's IAM user, in the background so it stays off the solves' cores. Open for tests, which
 *  answer [call] themselves. */
open class AwsCli(private val aws: AwsConfig) {
    open fun call(vararg args: String): String {
        val cmd = Background.prefix + listOf("aws", "--region", aws.region, "--profile", aws.profile, "--output", "text") + args
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "aws ${args.take(2).joinToString(" ")} failed: ${out.take(ERROR_CHARS)}" }
        return out
    }

    fun image(arch: String = "x86_64"): String =
        (if (arch == "arm64") aws.arm64Ami else aws.ami) ?: call("ssm", "get-parameter", "--name",
            UBUNTU_IMAGE.replace("/amd64/", if (arch == "arm64") "/arm64/" else "/amd64/"), "--query", "Parameter.Value")

    /** Launch one node, tagged as the lab's, terminating itself when it powers off. */
    fun launch(name: String, userData: File, type: String, spot: Boolean = false): String = call(
        "ec2", "run-instances",
        // A one-time request that terminates when AWS reclaims the capacity, its price capped at on demand.
        *(if (spot) arrayOf("--instance-market-options", "MarketType=spot,SpotOptions={SpotInstanceType=one-time,InstanceInterruptionBehavior=terminate}") else emptyArray()),
        "--image-id", image(aws.sizes.firstOrNull { it.type == type }?.arch ?: "x86_64"),
        "--instance-type", type,
        "--key-name", aws.keyName,
        "--security-group-ids", aws.securityGroup,
        *(aws.subnet?.let { arrayOf("--subnet-id", it) } ?: emptyArray()),
        *(aws.instanceProfile?.let { arrayOf("--iam-instance-profile", "Name=$it") } ?: emptyArray()),
        "--instance-initiated-shutdown-behavior", "terminate",
        "--user-data", "file://${userData.absolutePath}",
        "--metadata-options", "HttpTokens=required",
        "--block-device-mappings", "DeviceName=/dev/sda1,Ebs={VolumeSize=$VOLUME_GB,VolumeType=gp3,DeleteOnTermination=true}",
        "--tag-specifications",
        "ResourceType=instance,Tags=[{Key=$TAG,Value=true},{Key=Name,Value=$name}]",
        "ResourceType=volume,Tags=[{Key=$TAG,Value=true}]",
        "--query", "Instances[0].InstanceId",
    )

    /** Admit SSH from the lab machine's current public address, which a home connection can change. */
    open fun admitThisMachine() {
        val ip = ProcessBuilder("curl", "-fsS", "https://checkip.amazonaws.com").start().let { p ->
            p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
        }
        if (ip.isEmpty()) return
        runCatching {
            call("ec2", "authorize-security-group-ingress", "--group-id", aws.securityGroup, "--protocol", "tcp", "--port", "22",
                "--cidr", "$ip/32")
        } // already admitted is an error from the CLI, and the only one expected here
    }

    fun awaitRunning(id: String) {
        call("ec2", "wait", "instance-running", "--instance-ids", id)
    }

    /** [id]'s state (`running`, `shutting-down`, `terminated`, …) and the reason AWS gives for it. */
    fun state(id: String): Pair<String, String> = call(
        "ec2", "describe-instances", "--instance-ids", id,
        "--query", "Reservations[0].Instances[0].[State.Name, StateReason.Code]",
    ).trim().split(Regex("\\s+")).let { (it.getOrElse(0) { "unknown" }) to (it.getOrElse(1) { "" }) }

    fun publicIp(id: String): String =
        call("ec2", "describe-instances", "--instance-ids", id, "--query", "Reservations[0].Instances[0].PublicIpAddress")

    fun terminate(ids: Collection<String>) {
        if (ids.isNotEmpty()) call("ec2", "terminate-instances", "--instance-ids", *ids.toTypedArray(), "--query", "TerminatingInstances[].InstanceId")
    }

    /** The lab's instances that are pending or running. */
    fun running(): List<String> = call(
        "ec2", "describe-instances",
        "--filters", "Name=tag:$TAG,Values=true", "Name=instance-state-name,Values=pending,running",
        "--query", "Reservations[].Instances[].InstanceId",
    ).split(Regex("\\s+")).filter { it.isNotEmpty() }

    private companion object {
        const val UBUNTU_IMAGE = "/aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id"
        const val TAG = "eignex-lab"
        const val VOLUME_GB = 100
        const val ERROR_CHARS = 2000
    }
}

/** An instance as the lab reaches it: SSH as `ubuntu`, with the lab's key, in the background. */
open class Ssh(
    private val instance: String,
    private val ip: String,
    private val aws: AwsConfig,
    private val knownHosts: Path,
    /** Checked every second while a command runs: a long step (a build, a corpus fetch) then stops at once on a cancel,
     *  and the job's instances are terminated rather than left to finish it. */
    private val cancelled: () -> Boolean = { false },
) {
    private fun command(script: String): List<String> = Background.prefix + listOf(
        "ssh", "-i", aws.keyFile,
        "-o", "BatchMode=yes",
        "-o", "StrictHostKeyChecking=accept-new",
        "-o", "HostKeyAlias=eignex-lab-${aws.region}-$instance",
        "-o", "CheckHostIP=no",
        "-o", "UserKnownHostsFile=$knownHosts",
        "-o", "ConnectTimeout=15",
        "-o", "ServerAliveInterval=30",
        "-o", "ServerAliveCountMax=4",
        "ubuntu@$ip",
        "bash -c ${quote(script)}",
    )

    /** Start [script]; with [mergeErrors] off, SSH's own messages and the remote shell's stderr are left out of the
     *  output, as a case's record must be: a "Timeout, server not responding" once landed in the middle of one. */
    open fun start(script: String, mergeErrors: Boolean = true): Process = ProcessBuilder(command(script))
        .apply { if (mergeErrors) redirectErrorStream(true) else redirectError(ProcessBuilder.Redirect.DISCARD) }
        .start()

    /**
     * Run [script] to its end, at most [timeoutSec]; its exit (255 when SSH itself failed) and its output. A connection
     * the lab machine's own network dropped is tried again, backing off to about a quarter of an hour in all, unless
     * [retries] is off: the scripts it runs (a build, a select, a sync) pick up where a cut-off run left them. A refused
     * connection, as from an instance still booting, is returned at once for its caller to wait out.
     */
    fun exec(script: String, timeoutSec: Long, cancellable: Boolean = true, retries: Boolean = true): Pair<Int, String> {
        val cancelled = if (cancellable) cancelled else { { false } }
        var attempt = 1
        while (true) {
            val (exit, out) = execOnce(script, timeoutSec, cancelled)
            if (exit != SSH_FAILED || !NETWORK.containsMatchIn(out) || !retries || attempt >= NETWORK_RETRY.attempts || cancelled()) return exit to out
            val until = System.currentTimeMillis() + NETWORK_RETRY.delayAfter(attempt)
            while (System.currentTimeMillis() < until && !cancelled()) Thread.sleep(POLL_MS)
            attempt++
        }
    }

    /**
     * Run [script] once. A cancelled one is stopped on the instance too, not only its SSH connection, whose end leaves
     * a command without a terminal running: SSH starts the remote command in a session of its own, so the script first
     * writes its process group to a file, and on a cancel the group gets SIGTERM, then SIGKILL two seconds later.
     */
    private fun execOnce(script: String, timeoutSec: Long, cancelled: () -> Boolean): Pair<Int, String> {
        val group = "/tmp/eignex-lab-step-${UUID.randomUUID()}"
        val process = start("ps -o pgid= -p $$ > $group; bash -c ${quote(script)}; e=$?; rm -f $group; exit ${'$'}e")
        val output = StringBuilder()
        val reader = thread(isDaemon = true) { output.append(process.inputStream.bufferedReader().readText()) }
        val deadline = System.currentTimeMillis() + timeoutSec * MS_PER_SEC
        while (!process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
            if (cancelled()) {
                process.destroyForcibly()
                runCatching {
                    execOnce("g=$(tr -d ' ' < $group 2>/dev/null) && [ -n \"${'$'}g\" ] && " +
                        "{ kill -TERM -- -${'$'}g; sleep 2; kill -KILL -- -${'$'}g; } 2>/dev/null; rm -f $group; true", STOP_TIMEOUT_SEC) { false }
                }
                throw JobCancelled("cancelled on $instance")
            }
            if (System.currentTimeMillis() > deadline) {
                process.destroyForcibly()
                reader.join(READ_WAIT_MS)
                return TIMED_OUT to output.toString()
            }
        }
        reader.join(READ_WAIT_MS)
        return process.exitValue() to output.toString()
    }

    /** [exec] that fails unless [script] succeeds. */
    fun run(script: String, timeoutSec: Long): String {
        val (exit, out) = exec(script, timeoutSec)
        check(exit == 0) { "on $ip (exit $exit): ${out.takeLast(OUTPUT_CHARS)}" }
        return out
    }

    fun download(remote: String, destination: Path, timeoutSec: Long) {
        downloadStream("cat -- ${quote(remote)}", destination, timeoutSec)
    }

    internal fun downloadStream(
        script: String,
        destination: Path,
        timeoutSec: Long,
        onFailure: (Int, FileTransferFailure) -> Unit = { _, _ -> },
    ): Int = retryFileTransfer(NETWORK_RETRY, cancelled, onFailure) {
        receiveFile(start(script, mergeErrors = false), destination, timeoutSec, cancelled)
    }

    companion object {
        const val TIMED_OUT = -1
        private const val SSH_FAILED = 255
        /** SSH failing because the lab machine's network is down, rather than the instance refusing it. */
        private val NETWORK = Regex("Network is unreachable|No route to host|Connection timed out|Operation timed out|" +
            "Connection reset|Broken pipe|Could not resolve|Connection closed by remote host")
        private val NETWORK_RETRY = Backoff(attempts = 8, baseMs = 15_000, maxMs = 300_000)
        private const val READ_WAIT_MS = 5_000L
        private const val POLL_MS = 1000L
        private const val MS_PER_SEC = 1000L
        private const val OUTPUT_CHARS = 3000
        /** The most stopping a cancelled script's process group on the instance may take. */
        private const val STOP_TIMEOUT_SEC = 30L
    }
}

internal fun recordOutputTransferFailure(
    case: Path,
    stage: String,
    primary: Throwable,
    write: (Path, String) -> Unit = { path, text -> path.toFile().writeText(text) },
) {
    var reportFailed = false
    for ((name, text) in listOf(
        "out.transfer-status" to "incomplete\tstage=$stage\n",
        "out.transfer-error" to "${primary.message ?: primary::class.simpleName}\n",
    )) {
        try {
            write(case.resolve(name), text)
        } catch (report: Throwable) {
            reportFailed = true
            if (report !== primary) primary.addSuppressed(report)
        }
    }
    if (reportFailed) throw primary
}

/**
 * An AWS instance as an [ExecutionHost]: worktrees under `~/work`, the corpus under `~/corpus`, cases run over SSH
 * under `timeout`, each record copied back into the job's local directory as the case ends.
 */
class AwsHost(
    val instance: String,
    val ssh: Ssh,
    override val cores: Int,
    private val solveJavaOpts: String,
    private val measurement: Measurement = Measurement.NONE,
    /** A profile job: its output and its `$OUT` directory come back whatever its exit. */
    private val profileJob: Boolean = false,
    /** Why the instance is gone, when AWS reports it shut down or terminated; null while it runs. */
    private val gone: () -> String? = { null },
    /** Told each case's [CasePhase] as it changes, for the job's page and its JSON. */
    private val onPhase: (Int, CasePhase) -> Unit = { _, _ -> },
    /** How often and how long a case whose SSH connection dropped is run again. */
    private val retry: Backoff = SSH_RETRY,
    /** Where the job's case files live on the instance: a node runs several jobs at once, each in its own. */
    private val jobDir: String = "$HOME/job",
    /** The cases at once here, whatever the job's `parallel` says; null follows the job's, up to the cores. */
    private val parallel: Int? = null,
    /** The node's case slots, which this job's cases share with every other job on the node; null: no limit past
     *  the job's own. */
    private val slots: NodeSlots? = null,
    /** The job these cases are of, as it waits for [slots]. */
    private val jobId: Long = 0,
    private val repository: String = Repositories.DEFAULT,
    override val nodeTags: Set<String> = emptySet(),
    override val cpu: CpuCapabilities? = null,
    private val cancelled: () -> Boolean,
) : ExecutionHost {
    private val recordsJfr get() = measurement.recordsJfr
    private val compilation get() = measurement.compilationRecording
    override val id get() = instance
    override val corpus = "$HOME/corpus"
    override val yieldsToUpdates = false
    override val yieldsToPriority = false
    override val maxParallel get() = cores

    override fun parallel(requested: Int): Int = parallel ?: super.parallel(requested)

    override fun acquire(cores: Int): Boolean = slots?.let { it.acquire(jobId, NodePool.caseSlots(cores, it.capacity)) } ?: true

    override fun release(cores: Int) {
        slots?.let { it.release(NodePool.caseSlots(cores, it.capacity)) }
    }

    override fun worktree(sha: String): String {
        Repositories.requireAllowed(repository)
        val namespace = if (repository == Repositories.DEFAULT) "" else repository.substringAfter('/').lowercase() + "/"
        return "$HOME/work/$namespace${sha.take(SHA_DIR)}"
    }

    override fun select(worktree: String, args: String): List<String> {
        val opts = "-Dklause.bench.corpusCache=$corpus -Dklause.workspace.root=$worktree"
        val out = ssh.run(
            "$ENV; cd ${quote("$worktree/klause-bench")} && JAVA_OPTS=${quote(opts)} KLAUSE_BENCH_CORPUS_MAX_GB=off " +
                "./build/install/klause-bench/bin/klause-bench select $args 2>/dev/null",
            SELECT_TIMEOUT_SEC,
        )
        return out.lines()
    }

    /**
     * Run [command], again from the start when SSH itself dropped before the case reported its exit: the lab machine's
     * own network drops for minutes at a time, so the retries back off from 15 s to 5 min, about a quarter of an hour
     * in all, before the job fails. A cancel ends the wait.
     */
    override fun run(command: Command, dir: Path): Int {
        var attempt = 1
        while (true) {
            try {
                return runOnce(command, dir, attempt)
            } catch (e: SshDropped) {
                // A dropped connection to an instance AWS has taken back is no network trouble to wait out.
                runCatching(gone).getOrNull()?.let { reason ->
                    Termination.record(Termination.file(dir, command.index), Termination.hostLost("$instance is gone ($reason)"))
                    throw HostLost("$instance is gone ($reason)")
                }
                if (attempt >= retry.attempts || cancelled()) throw IllegalStateException(e.message, e)
                val until = System.currentTimeMillis() + retry.delayAfter(attempt)
                report(command, CasePhase.Phase.RETRY_BACKOFF, attempt, retryAt = until)
                while (System.currentTimeMillis() < until && !cancelled()) Thread.sleep(POLL_MS)
                attempt++
            }
        }
    }

    private class SshDropped(message: String) : Exception(message)

    private fun report(command: Command, phase: CasePhase.Phase, attempt: Int, retryAt: Long? = null, elapsedMs: Long? = null) {
        val now = System.currentTimeMillis()
        runCatching { onPhase(command.index, CasePhase(phase, now, attempt, retry.attempts, retryAt, elapsedMs)) }
    }

    private fun runOnce(command: Command, dir: Path, attempt: Int): Int {
        val i = command.index
        val measurementId = if (measurement != Measurement.NONE) UUID.randomUUID().toString() else null
        val profile = measurementId?.let { "$jobDir/cases/$i/profile-$it" }
        val options = profile?.takeIf { recordsJfr }?.let { CliMeasurements.options(it, compilation) }.orEmpty()
        val runtimeOptions = "$solveJavaOpts -XX:ActiveProcessorCount=${command.cores}".trim()
        val javaOptions = "$runtimeOptions $options".trim()
        val script = """
            $ENV
            export JOB_DIR=$jobDir KLAUSE_DEBUG=true KLAUSE_CLI_OPTS=${quote(javaOptions)} KLAUSE_CLI_PREFLIGHT_OPTS=${quote(runtimeOptions)}
            export OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1 OMP_NUM_THREADS=1
            mkdir -p ${'$'}JOB_DIR/cases
            ${profile?.let { "mkdir -p ${quote(it)}; export EIGNEX_LAB_PROFILE_DIR=${quote(it)}" }.orEmpty()}
            timeout -k 30 ${command.timeoutSec} bash -c ${quote(command.cmd)} > ${'$'}JOB_DIR/$i.out 2> ${'$'}JOB_DIR/$i.err
            e=${'$'}?
            echo "exit=${'$'}e"
            if [ ${'$'}e -eq 137 ] || [ ${'$'}e -eq 143 ]; then
              sudo -n dmesg -T 2>/dev/null | grep -iE 'killed process|out of memory' | tail -n 3 | sed 's/^/$KERNEL/'
            fi
            (cd ${'$'}JOB_DIR && ${outputFiles(i)} | xargs -0 -r sha256sum --) 2>/dev/null | sed 's/^/$OUTPUT_SHA/'
            echo "$RECORD"
            cat ${'$'}JOB_DIR/cases/$i/*.json 2>/dev/null || true
        """.trimIndent()
        val started = System.currentTimeMillis()
        report(command, CasePhase.Phase.EXECUTING, attempt)
        val process = ssh.start(script, mergeErrors = false)
        val output = StringBuilder()
        val reader = thread(isDaemon = true) { output.append(process.inputStream.bufferedReader().readText()) }
        val deadline = System.currentTimeMillis() + (command.timeoutSec + SSH_GRACE_SEC) * MS_PER_SEC
        while (!process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
            if (cancelled() || System.currentTimeMillis() > deadline) {
                report(command, CasePhase.Phase.TERMINATING, attempt)
                Termination.record(Termination.file(dir, i),
                    if (cancelled()) Termination.CANCEL else Termination.timeout(command.timeoutSec))
                process.destroyForcibly()
                ssh.exec("pkill -f -- '${jobDir.removePrefix("$HOME/")}/cases/$i([^0-9]|${'$'})' || true", KILL_TIMEOUT_SEC, cancellable = false)
                return if (cancelled()) CANCELLED else TIMEOUT
            }
        }
        reader.join(READ_WAIT_MS)
        val text = output.toString()
        val exit = Regex("""(?m)^exit=(\d+)$""").find(text)?.groupValues?.get(1)?.toInt()
            ?: throw SshDropped("case $i on $instance: no exit reported (ssh exit ${process.exitValue()}): ${text.takeLast(ERROR_CHARS)}")
        val record = text.substringAfter("$RECORD\n", "").trim()
        // A record cut or garbled on the way back is a dropped connection, rerun as one, never stored.
        if (record.isNotEmpty() && runCatching { Json.parseToJsonElement(record) }.isFailure) {
            throw SshDropped("case $i on $instance: its record came back garbled")
        }
        if (record.isNotEmpty()) {
            dir.resolve("cases").resolve(i.toString()).createDirectories().resolve("record.json").toFile().writeText(record)
        }
        val result = if (exit == TIMEOUT_EXIT_CODE) TIMEOUT else exit
        dir.resolve("$i.exit").toFile().writeText(if (result == TIMEOUT) "timeout\n" else "$exit\n")
        val kernel = text.substringBefore("$RECORD\n").lines().filter { it.startsWith(KERNEL) }.map { it.removePrefix(KERNEL) }
        Termination.remote(exit, System.currentTimeMillis() - started, command.timeoutSec, kernel)
            ?.let { Termination.record(Termination.file(dir, i), it) }
        val elapsedMs = runCatching { Json.parseToJsonElement(record).jsonObject["elapsedMs"]?.jsonPrimitive?.longOrNull }.getOrNull()
        report(command, CasePhase.Phase.TRANSFERRING_OUTPUT, attempt, elapsedMs = elapsedMs)
        if (profileJob) fetchOutDir(i, dir)
        fetchOutput(i, dir, outputHashes(text.substringBefore("$RECORD\n")))
        if (profile != null) {
            val files = dir.resolve("cases/$i/profile-$measurementId").createDirectories()
            CliMeasurements.writeManifest(files, i, attempt, requireNotNull(measurementId), instance, command.cmd, exit, javaOptions,
                record, macOs = false, compilation = compilation, recordsJfr = recordsJfr)
            for (name in CliMeasurements.artifacts(compilation, recordsJfr)) {
                // HotSpot's compilation log runs to hundreds of MB, and compresses tenfold: it comes back gzipped.
                if (name == CliMeasurements.COMPILATION_LOG) {
                    ssh.downloadStream("gzip -1 -c -- ${quote("$profile/$name")}", files.resolve(CliMeasurements.COMPILATION_LOG_GZ), LOG_TRANSFER_SEC)
                } else {
                    ssh.download("$profile/$name", files.resolve(name), ARTIFACT_TRANSFER_SEC)
                }
            }
        }
        return result
    }

    /**
     * Every case's output, whatever its exit and whether its record explains it, as a local case keeps it: `<i>.out` and
     * `<i>.err`, and the native solver stdout beside its record (`cases/<i>/<name>.out`) under its own name, complete.
     * They come back in one tar, each checked against the SHA-256 the instance took of it ([hashes]); a file missing or
     * changed on the way, or a transfer that fails, is written to `<i>.output.transfer-error`, and what did arrive stays.
     */
    private fun fetchOutput(i: Int, dir: Path, hashes: Map<String, String>) {
        val archive = dir.resolve("$i.output.tar")
        val failure = dir.resolve("$i.output.transfer-error")
        runCatching {
            Files.deleteIfExists(failure)
            // A run of the case before this one may have left output this run did not write.
            CaseFile.stdout(dir, i).forEach { Files.deleteIfExists(dir.resolve(it.path)) }
            ssh.downloadStream("cd ${quote(jobDir)} && ${outputFiles(i)} | tar -c --null -T -", archive, ARTIFACT_TRANSFER_SEC)
            val unpack = ProcessBuilder("tar", "-x", "-f", archive.toString(), "-C", dir.toString()).redirectErrorStream(true).start()
            val log = unpack.inputStream.bufferedReader().readText()
            check(unpack.waitFor() == 0) { "unpacking the case's output failed: ${log.take(ERROR_CHARS)}" }
            val wrong = hashes.filter { (path, sha) ->
                val file = dir.resolve(path)
                !Files.isRegularFile(file) || CaseFile.sha256(file) != sha
            }.keys
            check(wrong.isEmpty()) { "missing or changed on the way: ${wrong.sorted().joinToString()}" }
        }.onFailure { error -> runCatching { failure.toFile().writeText("${error.message ?: error::class.simpleName}\n") } }
        Files.deleteIfExists(archive)
    }

    /**
     * A profile case's `$OUT` directory, packed on the instance and unpacked under its case in the job directory, up to
     * [OUT_CAP_BYTES]: files go smallest first until the next would pass it, and the rest are listed with their sizes in
     * `out/omitted.tsv` instead, so one job's reports cannot fill the lab machine's disk. The instance is terminated
     * after, so an omitted file is gone; a job that needs it writes less, or summarises.
     */
    private fun fetchOutDir(i: Int, dir: Path) {
        val case = dir.resolve("cases/$i").createDirectories()
        val archive = case.resolve("out.tar")
        var stage = "transfer"
        runCatching {
            val attempt = ssh.downloadStream(packOut("$jobDir/cases/$i", OUT_CAP_BYTES), archive,
                ARTIFACT_TRANSFER_SEC) { number, error ->
                case.resolve("out.transfer-attempts.tsv").toFile().appendText(
                    "$number\t${error.exit ?: "timeout"}\tfailed\n")
            }
            stage = "unpack"
            val unpack = ProcessBuilder("tar", "-x", "-f", archive.toString(), "-C", case.toString())
                .redirectErrorStream(true).start()
            receiveFile(unpack, case.resolve("out.unpack.log"), ARTIFACT_TRANSFER_SEC, cancelled)
            Files.deleteIfExists(archive)
            case.resolve("out.transfer-status").toFile().writeText("complete\tattempt=$attempt\n")
        }.onFailure { error ->
            recordOutputTransferFailure(case, stage, error)
        }
    }

    companion object {
        /** The most of a profile case's `$OUT` that comes back from its instance. */
        const val OUT_CAP_BYTES = 1L shl 30

        /**
         * The shell step that writes `out` under [caseDir] to stdout as a tar of at most [cap] bytes of files, smallest
         * first, with `out/omitted.tsv` (size, path) listing the files left out when any are.
         */
        internal fun packOut(caseDir: String, cap: Long): String =
            "cd ${quote(caseDir)} && rm -f out/omitted.tsv .out-keep && touch .out-keep && " +
                "find out -type f -printf '%s\\t%p\\n' | sort -n | " +
                "awk -F'\\t' -v cap=$cap '{ t += \$1; if (t <= cap) print \$2 > \".out-keep\"; else print > \"out/omitted.tsv\" }' && " +
                "{ test -f out/omitted.tsv && echo out/omitted.tsv >> .out-keep; true; } && tar -c -T .out-keep"

        /**
         * The shell step, run in the job's directory on the instance, that lists case [i]'s output NUL-separated: its
         * `<i>.out` and `<i>.err`, and the regular files directly in `cases/<i>/` other than its record, which is its
         * native solver stdout. Its captures and a profile's `out/`, in directories, come back on their own.
         */
        internal fun outputFiles(i: Int): String =
            "find $i.out $i.err cases/$i -maxdepth 1 -type f ! -name '*.json' -print0 2>/dev/null"

        /** The SHA-256 the instance took of each output file ([OUTPUT_SHA] lines of [text]), by its path in the job's
         *  directory. A name `sha256sum` had to escape is left out, unchecked. */
        internal fun outputHashes(text: String): Map<String, String> =
            text.lines().filter { it.startsWith(OUTPUT_SHA) }.mapNotNull { line ->
                Regex("""([0-9a-f]{64}) [ *](.+)""").matchEntire(line.removePrefix(OUTPUT_SHA))?.destructured?.let { (sha, path) -> path to sha }
            }.toMap()

        const val HOME = "/home/ubuntu"
        const val ENV = "export JAVA_HOME=/opt/jdk PATH=/opt/jdk/bin:/usr/local/bin:${'$'}PATH"
        private const val SHA_DIR = 12
        private const val RECORD = "----eignex-lab-record----"
        /** Marks the instance's kernel log lines about killed processes, sent back for a case that ended by signal. */
        private const val KERNEL = "kernel-log: "
        /** Marks the instance's SHA-256 of each output file of a case, sent back before its record ([outputHashes]). */
        private const val OUTPUT_SHA = "output-sha256: "
        private const val SELECT_TIMEOUT_SEC = 3600L
        private const val SSH_GRACE_SEC = 120L
        private const val KILL_TIMEOUT_SEC = 30L
        private const val POLL_MS = 1000L
        private const val READ_WAIT_MS = 5_000L
        private const val ARTIFACT_TRANSFER_SEC = 120L
        private const val LOG_TRANSFER_SEC = 600L
        private const val MS_PER_SEC = 1000L
        private const val ERROR_CHARS = 2000
        private const val TIMEOUT_EXIT_CODE = 124
        internal val SSH_RETRY = Backoff(attempts = 8, baseMs = 15_000, maxMs = 300_000)

        /** The runner's exits for a case cut off by a cancel or by its timeout. */
        const val CANCELLED = -1000
        const val TIMEOUT = -1001
    }
}

internal fun claimNodeJob(config: Config, store: Store, jobId: Long): Job? {
    // Assignment metadata is published before AwsJob opens setup.log.
    config.createJobDir(jobId)
    return store.claimForNode(jobId)
}

/**
 * Runs AWS work on a pool of nodes: EC2 instances the lab launches when the queue's pressure calls for one
 * ([NodePool], [LaunchGate]), so none runs while nothing waits for AWS. A node, once booted, takes work until there is
 * none for it: it first joins a shared job already running that still has iterations no host has claimed, wherever it
 * runs, the local machine included, building that job's commits, and otherwise claims the next queued job it may run.
 * It runs several jobs at once, their cases sharing its slots, one case per two physical cores ([NodeSlots]), each job
 * within its own `parallel`; a profile or measuring job keeps it to itself.

 * Every host on a job pulls its iterations from the store one at a time ([Store.claimIteration]), every arm of an
 * iteration on the host that claimed it, so each comparison is made on one machine; the hosts on a job are its crew
 * ([Crews]), and the last to leave ends it ([Runner.leave]).
 * Records come back as cases end, so the job's page, comparisons and CSVs read as a local job's. A node with nothing
 * to take for [AwsConfig.nodeIdleMin] minutes is terminated.
 *
 * The pool outlives the lab's restarts, a lab update's included: each node is kept in `aws/pool.json` ([PoolState]),
 * and a restarted worker adopts the ones still running there ([recover]) instead of terminating them. Nothing lets an
 * instance outlive the lab's control: every one powers itself off, and so terminates, after [AwsConfig.maxHours] from
 * its launch, which adoption keeps, and after [AwsConfig.nodeWatchdogMin] without the lab's [heartbeat]; adoption fails
 * closed, terminating an instance it has any doubt about; and the sweep terminates, every few minutes, any lab
 * instance that is not one of the worker's nodes.
 */
class AwsWorker(
    private val runner: Runner,
    private val store: Store,
    private val config: Config,
    initial: AwsConfig,
    /** The AWS CLI for the settings at hand; a test answers it itself. */
    private val cliFor: (AwsConfig) -> AwsCli = ::AwsCli,
    /** SSH to an instance (its id, address, the settings, the known hosts and what cancels a step); a test fakes it. */
    private val sshFor: (String, String, AwsConfig, Path, () -> Boolean) -> Ssh = ::Ssh,
    /** How often the worker polls, sweeps orphans and touches its nodes' heartbeats; shorter only in tests. */
    private val pollMs: Long = POLL_MS,
    private val sweepMs: Long = SWEEP_MS,
    private val heartbeatMs: Long = HEARTBEAT_MS,
    /** How often a node looks for work while it has room; shorter only in tests. */
    private val nodePollMs: Long = NODE_POLL_MS,
) {
    /** The settings, read again from `aws.properties` on every poll: a raised quota or a new threshold applies to the
     *  next decision without restarting the lab. */
    @Volatile
    private var aws: AwsConfig = initial
    private val cli: AwsCli get() = cliFor(aws)
    private val settings = config.dataDir.resolve("aws").resolve("aws.properties")
    /** vCPUs the nodes up and launching hold of the quota. */
    private val busy = AtomicInteger()
    /** Numbers the nodes' names. */
    private val launched = AtomicInteger()
    /** Launches in progress, which may wait a while for spot capacity; the sweep holds off while any is. */
    private val launching = AtomicInteger()
    /** Launches in progress bought on demand for a profile job, so a second is not bought for the same one. */
    private val launchingOnDemand = AtomicInteger()
    private val nodes = ConcurrentHashMap<String, Node>()
    /** The finished runs the pressure's estimates learn from ([Estimates]), so it counts realistic work rather than
     *  every case's whole timeout. */
    private val history = EstimateHistory(store, config.cores)
    private val gate = LaunchGate()
    /** The pressure at the last poll, in core-hours; a launch waiting for spot gives up once it is gone. */
    @Volatile
    private var pressure = 0.0
    /** What kept the last poll from launching a node ([LaunchGate.blocker]), for the pages. */
    @Volatile
    private var blocker: String? = null
    /** The size the queue called for at the last poll, which a manual launch that names none gets ([ManualLaunch]):
     *  the cores of the largest waiting case, at least the smallest size; null when none fits the quota left. */
    @Volatile
    private var queueSize: InstanceSize? = null
    private val manualFile = ManualLaunch.file(config)

    /** Whether any node is up or launching: the local-only jobs then go first among equal priorities ([Store.next]).
     *  A lab update no longer waits for it: the pool is adopted again after the restart ([recover]). */
    val active: Boolean get() = busy.get() > 0 || launching.get() > 0
    private val knownHosts = config.dataDir.resolve("aws").resolve("known_hosts")
    private val nodesFile = config.dataDir.resolve("aws").resolve(NODES_FILE)
    private val poolFile = config.dataDir.resolve("aws").resolve(POOL_FILE)
    /** Whether the startup's adoption ([recover]) has run: until it has, no node launches and the pool's state file is
     *  not rewritten, so a failed start loses none of the nodes in it. */
    @Volatile
    internal var poolRecovered = false
        private set

    fun loop() {
        knownHosts.parent.createDirectories()
        var swept = System.currentTimeMillis()
        var beat = 0L
        while (true) {
            runCatching { AwsConfig.load(settings)?.let { aws = it } }.onFailure { println("aws: settings not reread: ${it.message}") }
            if (!poolRecovered) {
                // Tried again at every poll while the CLI fails, as with the lab machine's network down at start.
                runCatching { recover() }.onFailure { println("aws: recovery failed, trying again: ${it.message}") }
                if (!poolRecovered) {
                    Thread.sleep(pollMs)
                    continue
                }
                swept = System.currentTimeMillis()
            }
            if (System.currentTimeMillis() - beat >= heartbeatMs) {
                beat = System.currentTimeMillis()
                heartbeat()
            }
            runCatching { scale() }.onFailure { println("aws: ${it.message}") }
            runCatching { manual() }.onFailure { println("aws: manual launch failed: ${it.message}") }
            runCatching { report() }
            if (System.currentTimeMillis() - swept > sweepMs) {
                swept = System.currentTimeMillis()
                runCatching { sweep() }.onFailure { println("aws: sweep failed: ${it.message}") }
            }
            Thread.sleep(pollMs)
        }
    }

    /**
     * Touch each node's heartbeat file, whose age its watchdog checks ([bootstrap]): a node the lab has not touched for
     * [AwsConfig.nodeWatchdogMin] powers itself off. Each node on a thread of its own, so one that does not answer
     * holds up neither the others nor the poll.
     */
    private fun heartbeat() {
        for (node in nodes.values.filter { it.ip.isNotEmpty() }) {
            thread(isDaemon = true, name = "aws-heartbeat-${node.instance}") {
                runCatching { ssh(node).exec(TOUCH_HEARTBEAT, SSH_STEP_SEC, cancellable = false, retries = false) }
            }
        }
    }

    private fun ssh(node: Node, cancelled: () -> Boolean = { false }) = sshFor(node.instance, node.ip, aws, knownHosts, cancelled)

    /**
     * The work waiting for a node, as [Demand]s: the queued jobs a node may take, in queue order, and the iterations no
     * host has claimed of the shared jobs running, wherever they run, the local machine included ([Store.helpable]). The
     * hosts on each running one come from the crews ([Crews]), so a node spreads over jobs of equal priority. The
     * one the local machine takes next, being free or about to give way to it ([Store.localNext], told whether a node
     * is up or launching as the runner's [Store.next] is), is left to it. Work the local machine could run below
     * [AwsConfig.nodeMinPriority] is marked [Demand.belowFloor]: it launches no node, but a node already up with nothing
     * else to do takes it ([NodePool.pick]). A queued AWS job no size within the quota can hold fails here, as no node
     * could ever take it.
     */
    private fun demands(): Pair<List<Demand>, List<Demand>> {
        val localNext = store.localNext(active, config.localNodeTags)
        val queued = store.nodeQueue().mapNotNull { job ->
            val spec = job.experiment ?: return@mapNotNull null
            if (job.id == localNext) return@mapNotNull null
            val awsOnly = spec.host == Experiments.AWS_HOST
            val cores = Experiments.arms(spec).maxOf { it.cores }
            if (aws.sizeFor(cores, spec.nodeTags, spec.isProfile) == null && nodes.values.none {
                    it.size.cores >= cores && NodeTags.matches(spec.nodeTags, it.tags) && (!NodeTags.onDemand(spec.isProfile, spec.nodeTags) || it.onDemand)
                }) {
                if (awsOnly) store.claimForNode(job.id)?.let {
                    store.finish(job.id, Status.FAILED, "its cases need $cores cores, more than an instance within the ${aws.vcpuQuota}-vCPU quota has")
                }
                return@mapNotNull null
            }
            Demand(job.id, Estimates.pressureCoreHours(job, history::lookup, config.cores), cores, spec.isProfile, job.priority,
                Placement.belowFloor(spec.host, job.priority, aws.nodeMinPriority), nodeTags = spec.nodeTags, measures = spec.measuresCli)
        }
        val failing = runner.crews.failing()
        val running = store.helpable().mapNotNull { (job, unclaimed) ->
            val spec = job.experiment ?: return@mapNotNull null
            if (job.id in failing) return@mapNotNull null
            Demand(job.id, unclaimed * Estimates.caseCoreHours(job, history::lookup), Experiments.arms(spec).maxOf { it.cores },
                priority = job.priority, belowFloor = Placement.belowFloor(spec.host, job.priority, aws.nodeMinPriority),
                hosts = runner.crews.hosts(job.id).size, nodeTags = spec.nodeTags, measures = spec.measuresCli)
        }
        return queued to running
    }

    /** The pressure, in core-hours, that launches the first node: [AwsConfig.nodeLaunchLocalHours] of the local
     *  machine's cores, so a node launches only for a queue that would keep the local machine busy that long, never for
     *  a job or two. */
    private val threshold: Double get() = NodePool.threshold(config.cores, aws.nodeLaunchLocalHours)

    /** Launch a node when the pressure calls for one ([LaunchGate]), sized to what waits ([NodePool.size]). */
    private val launchingShapes = ConcurrentHashMap<String, NodeShape>()

    /** The nodes up or launching that take ordinary work, at the last poll: the count the next node's threshold
     *  follows ([NodePool.nextThreshold]). */
    @Volatile
    private var working = 0

    private fun scale() {
        val (queued, running) = demands().let { (q, r) -> q.filter { !it.belowFloor } to r.filter { !it.belowFloor } }
        val all = queued + running
        pressure = NodePool.pressure(all)
        val count = nodes.size + launching.get()
        // The nodes that take ordinary work, up or launching: an on-demand node kept for profile jobs neither covers
        // the pressure nor counts toward the next node's threshold.
        val ordinary = nodes.values.map { it.shape }.filter { it.ordinary } + launchingShapes.values.filter { it.ordinary }
        val covered = ordinary.sumOf { it.cores }
        val counted = ordinary.size
        working = counted
        val provisioned = nodes.values.flatMap { listOf(it.shape, NodeShape(it.size.cores, it.onDemand, it.size.tags(it.onDemand))) } + launchingShapes.values
        val dedicated = NodePool.dedicated(all, config.localNodeTags, provisioned)
        val plan = NodePool.launchPlan(aws.sizes, dedicated?.let(::listOf) ?: all, covered, aws.vcpuQuota - busy.get(), aws.nodeMaxVcpus)
        val size = plan?.size
        queueSize = size ?: NodePool.size(aws.sizes, 1, 0.0, covered, aws.vcpuQuota - busy.get(), aws.nodeMaxVcpus).takeIf { all.isEmpty() }
        val noRoom = when {
            count >= aws.maxInstances -> LaunchGate.AT_MAX
            !runner.recovered || Volumes.jobFiles(config) != null -> LaunchGate.NO_JOB_FILES
            size == null && all.isNotEmpty() -> LaunchGate.QUOTA_FULL
            else -> null
        }
        val now = System.currentTimeMillis()
        val sustainMs = if (dedicated == null) aws.nodeSustainMin * MS_PER_MIN else 0L
        val launchThreshold = if (dedicated == null) threshold else 0.0
        val cooldownMs = aws.nodeCooldownMin * MS_PER_MIN
        val launch = gate.shouldLaunch(now, pressure, counted, launchThreshold, size != null && noRoom == null, sustainMs, cooldownMs)
        blocker = if (launch) null else gate.blocker(now, pressure, counted, launchThreshold, noRoom, sustainMs, cooldownMs)
        if (!launch || size == null) return
        // Keep the ordinary profile market policy; explicit tag selectors may instead require spot.
        val untagged = all.all { it.nodeTags == null }
        val onDemand = if (untagged) queued.any { it.profile } && nodes.values.none { it.onDemand } && launchingOnDemand.get() == 0
            else checkNotNull(plan).onDemand
        // A node bought on demand for the profile jobs is sized for them alone: the rest of the pressure goes to spot
        // nodes, launched as it calls for them.
        val chosen = if (untagged && onDemand) {
            NodePool.launchPlan(aws.sizes, queued.filter { it.profile }, 0, aws.vcpuQuota - busy.get(), aws.nodeMaxVcpus)?.size ?: size
        } else size
        println("aws: pressure %.1f core-hours on $count nodes: launching a %s %s node".format(pressure, chosen.type, if (onDemand) "on-demand" else "spot-first"))
        launch(chosen, onDemand, spotOnly = checkNotNull(plan).spotOnly)
    }

    /**
     * Start the node a manual launch asked for ([ManualLaunch]), when its request waits: past the pressure threshold
     * and the sustain wait, but within `maxInstances` and the quota, checked again here against the pool as it now
     * stands. It counts as the gate's last launch, for the cooldown.
     */
    private fun manual() {
        val request = ManualLaunch.take(manualFile) ?: return
        val now = System.currentTimeMillis()
        val plan = ManualLaunch.decide(request, now, aws, queueSize, nodes.size + launching.get(), aws.vcpuQuota - busy.get()).getOrElse {
            println("aws: manual launch refused: ${it.message}")
            return
        }
        gate.manualLaunch(now)
        println("aws: manual launch of a ${plan.size.type} ${ManualLaunch.marketText(plan.onDemand)} node, asked from the ${request.by ?: "unknown"}")
        launch(plan.size, plan.onDemand, manual = true)
        report()
    }

    /** Reserve [size]'s vCPUs and launch a node on its own thread, which then runs it until it retires. */
    private fun launch(size: InstanceSize, onDemand: Boolean, manual: Boolean = false, spotOnly: Boolean = false) {
        val pending = UUID.randomUUID().toString()
        launchingShapes[pending] = NodeShape(size.cores, onDemand, size.tags(onDemand), ordinary = !onDemand || aws.onDemand || manual)
        busy.addAndGet(size.vcpus)
        launching.incrementAndGet()
        if (onDemand) launchingOnDemand.incrementAndGet()
        thread(isDaemon = true, name = "aws-node-${launched.get() + 1}") {
            val node = try {
                buy(size, onDemand, manual, spotOnly)
            } catch (e: Exception) {
                println("aws: no ${size.type} node launched: ${e.message}")
                null
            } finally {
                launchingShapes.remove(pending)
                launching.decrementAndGet()
                if (onDemand) launchingOnDemand.decrementAndGet()
            }
            if (node == null) busy.addAndGet(-size.vcpus) else run(node)
        }
    }

    /** One instance of [size], registered as a node at once so the sweep leaves it alone; [manual] when started by
     *  hand. */
    private fun buy(size: InstanceSize, onDemand: Boolean, manual: Boolean, spotOnly: Boolean): Node {
        runCatching { cli.admitThisMachine() }
        val userData = Files.createTempFile("eignex-lab-user-data", ".sh").toFile()
        try {
            userData.writeText(bootstrap())
            val name = "eignex-lab-node-${launched.incrementAndGet()}"
            val (id, market) = if (onDemand) cli.launch(name, userData, size.type) to ON_DEMAND else spotFirst(name, userData, size.type, manual, spotOnly)
            return Node(id, size, market, System.currentTimeMillis(), manual).also { nodes[id] = it }
                .also { println("aws: launched ${size.type} $market ${if (manual) "manual " else ""}node $id") }
                .also { savePool() }
        } finally {
            userData.delete()
        }
    }

    /**
     * A spot instance, asked for again every [SPOT_RETRY_MS] while AWS has no spot capacity or quota for it, and on
     * demand once [AwsConfig.spotWaitMin] minutes have passed, or at once when the account cannot launch spot at all,
     * if [AwsConfig.onDemand] allows. It gives up once the pressure it was launched for is gone, or, for a [manual]
     * launch, which no pressure called for, once `spotWaitMin` has passed without spot or on demand.
     */
    private fun spotFirst(name: String, userData: File, type: String, manual: Boolean = false, spotOnly: Boolean = false): Pair<String, String> {
        val since = System.currentTimeMillis()
        while (true) {
            val e = runCatching { return cli.launch(name, userData, type, spot = true) to SPOT }.exceptionOrNull()!!
            val message = e.message.orEmpty()
            val capacity = CAPACITY.find(message)?.value
            val waited = System.currentTimeMillis() - since
            if (!spotOnly && aws.onDemand && (NO_SPOT.containsMatchIn(message) || capacity != null && waited >= aws.spotWaitMin * MS_PER_MIN)) {
                println("aws: no spot for $type (${capacity ?: "spot not set up"}) after ${waited / MS_PER_SEC} s: launching on demand")
                return cli.launch(name, userData, type) to ON_DEMAND
            }
            if (capacity == null) throw e
            if (manual) check(waited < aws.spotWaitMin * MS_PER_MIN) { "no spot for $type within ${aws.spotWaitMin} min, and onDemand is off" }
            else check(pressure >= threshold) { "no spot for $type, and the work it was for is gone" }
            println("aws: no spot for $type yet ($capacity); trying again in ${SPOT_RETRY_MS / MS_PER_SEC} s")
            Thread.sleep(SPOT_RETRY_MS)
        }
    }

    /** An instance of the pool: its size, how it was bought, whether it was started by hand or adopted after a restart
     *  ([recover]), and what it is doing, for the pages. */
    private inner class Node(
        val instance: String, val size: InstanceSize, val market: String, val launchedAt: Long, val manual: Boolean = false,
        val adopted: Boolean = false,
    ) {
        val onDemand: Boolean get() = market == ON_DEMAND
        /** Whether it takes work that does not need an on-demand node: a spot node does; an on-demand one only with
         *  `onDemand=true`, or when started by hand. Otherwise it runs the profile jobs it was bought for and retires once
         *  none is left. */
        val ordinary: Boolean get() = !onDemand || aws.onDemand || manual
        val tags: Set<String> get() = NodeTags.observed(cpu,
            aws.nodeTags + aws.instanceTags[size.type.substringBefore('.')].orEmpty() + aws.instanceTags[size.type].orEmpty() + aws.instanceTags[instance].orEmpty(),
            cloud = true, spot = !onDemand, type = size.type)
        val shape: NodeShape get() = NodeShape(size.cores, onDemand, tags, ordinary)
        @Volatile var cpu: CpuCapabilities = CpuCapabilities()
        /** What it does while it has no job: booting, then idle. */
        @Volatile
        var state = "booting"
        /** The jobs it works on now, each with what it does for it. */
        val jobs = ConcurrentHashMap<Long, String>()
        /** Its case slots, which its jobs' cases share ([NodeSlots]). */
        val slots = NodeSlots(NodePool.slots(size.cores))
        /** The [Demand.exclusive] job it runs, beside which it takes nothing. */
        @Volatile
        var exclusive: Long? = null
        /** The exclusive job it empties for ([NodeWork.Wait]): it takes nothing more, and its jobs claim no more
         *  iterations, until that job is taken. */
        @Volatile
        var waitFor: Long? = null
        /** Why it retires once its jobs have ended: one failed on it, or its own loop did. It takes nothing more. */
        @Volatile
        var failure: String? = null
        /** Held while it sets a job up: its jobs share one clone, one Gradle cache and one corpus cache. */
        val setupLock = ReentrantLock()
        /** What it does, for the pages: each job's part, or its [state] with none. */
        val doing: String get() = jobs.entries.sortedBy { it.key }.joinToString("; ") { it.value }.ifEmpty { state } +
            (waitFor?.let { "; making room for job $it" } ?: "")
        @Volatile
        var ip = ""
        /** The commits built on it, each keyed with whether a profiling launcher was installed for it
         *  ([AwsBuildCache.builtKey]). */
        val built: MutableSet<String> = Collections.synchronizedSet(HashSet())
    }

    /**
     * A node's life: boot, unless [booted] already, then take work while it has room ([take]), each job on a thread of
     * its own ([work]) beside the others, until [NodePool.retire] says it is done, then terminate it. It is idle only
     * with no job at all. An adopted node's age counts from its first launch, and its idle time from its adoption. A
     * node whose work failed may be the reason: it takes nothing more, and retires once its other jobs have ended
     * rather than take the next job down with it.
     */
    private fun run(node: Node, booted: Boolean = false) {
        var why = "stopped"
        try {
            if (!booted) boot(node)
            var idleSince: Long? = System.currentTimeMillis()
            while (true) {
                val now = System.currentTimeMillis()
                if (node.jobs.isEmpty()) {
                    val since = idleSince ?: now.also { idleSince = it }
                    val failed = node.failure
                    if (failed != null) {
                        why = failed
                        break
                    }
                    val retire = NodePool.retire(now, node.launchedAt, since, aws.nodeIdleMin * MS_PER_MIN, NodePool.maxAgeMs(aws.maxHours))
                    if (retire != null) {
                        why = retire
                        break
                    }
                } else {
                    idleSince = null
                }
                val job = if (node.failure != null) null else runCatching { take(node) }.getOrElse {
                    node.failure = "failed: ${it.message}"
                    null
                }
                if (job != null) {
                    thread(isDaemon = true, name = "aws-${node.instance}-job-${job.id}") {
                        val healthy = runCatching { work(node, job) }.getOrDefault(false)
                        if (!healthy && node.failure == null) node.failure = "retired after its work failed"
                    }
                    // Room may be left for another job at once.
                    continue
                }
                Thread.sleep(nodePollMs)
            }
        } catch (e: Exception) {
            why = "failed: ${e.message}"
        } finally {
            retire(node, why)
        }
    }

    private fun boot(node: Node) {
        runner.withRetry(null, "waiting for ${node.instance}") { cli.awaitRunning(node.instance) }
        node.ip = runner.withRetry(null, "reading ${node.instance}'s address") {
            cli.publicIp(node.instance).also { check(it != "None") { "no public address yet" } }
        }
        savePool()
        val ssh = ssh(node)
        runner.withRetry(null, "waiting for ${node.instance} to boot", BOOT_WAIT) { ssh.run("test -f $READY", SSH_STEP_SEC) }
        node.cpu = probe(node)
        node.state = "idle"
        report()
    }

    private fun probe(node: Node): CpuCapabilities {
        val (exit, output) = ssh(node).exec(NodeTags.probeScript, SSH_STEP_SEC)
        check(exit == 0) { "cannot read node capabilities" }
        return NodeTags.parse(output)
    }

    /** The job [node] works on next beside the ones it runs, joined or claimed ([NodePool.pick]); null when none is
     *  for it or it has no room. While the corpus's volume is missing ([Volumes]), a node only joins jobs already
     *  running. A waiting lab update changes nothing: the node is adopted again after the restart ([recover]). It takes
     *  nothing before the runner's startup recovery ([Runner.recover]) or while the job files cannot be used, as a job's
     *  files are written there: the node stays up, idle, until they can. Nothing past its age, as it then retires once
     *  its jobs end, and nothing beside an exclusive job. It picks ([pick]) under the crews' lock ([Crews]), which the
     *  local machine takes and joins jobs under too, so no job ends while a node joins it. */
    private fun take(node: Node): AwsJob? {
        if (!runner.recovered || Volumes.jobFiles(config) != null) return null
        if (System.currentTimeMillis() - node.launchedAt >= NodePool.maxAgeMs(aws.maxHours)) return null
        if (node.exclusive != null) {
            node.waitFor = null
            return null
        }
        val claims = Volumes.missing(config) == null
        return synchronized(runner.crews.lock) { pick(node, claims) }
    }

    /** The job [node] joins or claims, a queued one only when [claims]; null when none is for it, or when it should
     *  empty for an exclusive job first ([NodeWork.Wait]). The job's slots are reserved on the node until its cases
     *  start ([reservation]). */
    private fun pick(node: Node, claims: Boolean): AwsJob? {
        val (queued, running) = demands()
        val work = NodePool.pick(node.shape, running, if (claims) queued else emptyList(), free = node.slots.free(), on = node.jobs.keys.toSet())
        node.waitFor = (work as? NodeWork.Wait)?.jobId
        val job = when (work) {
            null, is NodeWork.Wait -> return null
            is NodeWork.Help -> store.job(work.jobId)?.takeIf { it.status == Status.RUNNING } ?: return null
            is NodeWork.Claim -> claimNodeJob(config, store, work.jobId) ?: return null
        }
        val spec = checkNotNull(job.experiment)
        runner.crews.join(job.id, node.instance)
        node.jobs[job.id] = "taking job ${job.id}"
        if (spec.isProfile || spec.measuresCli) node.exclusive = job.id
        node.slots.reserve(job.id, reservation(node, job, spec))
        writeInstances(job.id)
        return AwsJob(job.id, spec)
    }

    /** The slots [job] holds on [node] until its cases start: as many cases at once as it runs here (its `parallel`,
     *  or the node's default) of its largest case, within the node; the whole node for an exclusive job. */
    private fun reservation(node: Node, job: Job, spec: ExperimentSpec): Int {
        val capacity = node.slots.capacity
        if (spec.isProfile || spec.measuresCli) return capacity
        val cases = if (spec.parallel == null) capacity else job.parallel
        return (cases * NodePool.caseSlots(Experiments.arms(spec).maxOf { it.cores }, capacity)).coerceIn(1, capacity)
    }

    /**
     * Work on [job] on [node], beside the other jobs it runs: set it up (build its commits; plan it when no host has),
     * one job's setup at a time on the node, then pull its iterations from the store ([Store.claimIteration]) beside the
     * other hosts on it, the local machine included, until none is left for this node, its cases sharing the node's
     * slots with the other jobs' ([NodeSlots]). False when the node should retire: its work failed, or AWS took it back.
     */
    private fun work(node: Node, job: AwsJob): Boolean {
        val host = host(node, job)
        var healthy = false
        var outcome: Dispatched? = null
        try {
            runner.onNode { settingUp(node, job) { setUp(node, host, job) } }
            node.jobs[job.id] = "running job ${job.id}"
            report()
            // Past its age a node takes no more iterations, so it retires before it powers itself off mid-case. Nor
            // while it empties for an exclusive job, or after another job failed on it.
            val maxAge = NodePool.maxAgeMs(aws.maxHours)
            val next = {
                val waiting = node.waitFor
                if (System.currentTimeMillis() - node.launchedAt >= maxAge || runner.crews.failed(job.id) ||
                    node.failure != null || waiting != null && waiting != job.id) null
                else store.claimIteration(job.id, node.instance)
            }
            val current = checkNotNull(store.job(job.id))
            outcome = runner.dispatch(current, job.dir, host, next, runner.render(current, host)) { }
            if (outcome == Dispatched.LOST) runner.log(job.id, "node ${node.instance} was taken back by the cloud provider")
            healthy = outcome != Dispatched.LOST
        } catch (e: NotJoined) {
            // A cancel stopped the node's setup, on the node too ([Ssh]): it is fit to take other work.
            healthy = store.cancelRequested(job.id)
            if (healthy) stopped(node, job) else runner.log(job.id, "node ${node.instance} did not join: ${e.message}")
        } catch (e: Exception) {
            // The job's own setup, or a case failing to run, fails the job; its other hosts take no more of it. A
            // cancel stopped what ran on the node for the job, which then takes other work rather than retire.
            if (store.cancelRequested(job.id)) {
                healthy = true
                outcome = Dispatched.CANCELLED
                stopped(node, job)
            } else {
                runner.crews.fail(job.id, e)
            }
        } finally {
            // Before it leaves the node: the node then takes nothing in its place.
            if (!healthy && node.failure == null) node.failure = "retired after its work failed"
            node.jobs.remove(job.id)
            node.slots.leave(job.id)
            if (node.exclusive == job.id) node.exclusive = null
            runner.leave(job.id, node.instance, outcome)
            writeInstances(job.id)
        }
        // Its case files came back as each case ended; each job on the node has a directory of its own, gone once it leaves.
        if (healthy) runCatching { host.ssh.exec("rm -rf ${quote(job.remoteDir)}", SSH_STEP_SEC, cancellable = false) }
        return healthy
    }

    /** Run [block], [job]'s setup on [node], once no other job sets up there: its jobs share one clone, one Gradle
     *  cache and one corpus cache. A cancel ends the wait. */
    private fun <T> settingUp(node: Node, job: AwsJob, block: () -> T): T {
        while (!node.setupLock.tryLock(1, TimeUnit.SECONDS)) {
            if (store.cancelRequested(job.id)) throw JobCancelled("cancelled while waiting for another job's setup on ${node.instance}")
        }
        try {
            return block()
        } finally {
            node.setupLock.unlock()
        }
    }

    /** Say that a cancel stopped what [node] did for [job]. */
    private fun stopped(node: Node, job: AwsJob) {
        runner.log(job.id, "cancel requested — stopped job ${job.id} on ${node.instance}; it takes other work")
    }

    /** A helper's setup failing: the job goes on without it. */
    private class NotJoined(cause: Throwable) : Exception(cause.message, cause)

    /**
     * The node's setup of [job]: resolve and build its commits, then plan it when no host has, or else fetch the corpora
     * its selections read, so its cases never fetch one side by side. The node that plans it pushes what it fetched to
     * the corpus bucket for the other hosts. A setup that fails while other hosts run the planned job is [NotJoined]:
     * the job goes on without this node.
     */
    private fun setUp(node: Node, host: AwsHost, job: AwsJob) {
        val current = checkNotNull(store.job(job.id))
        val spec = job.spec
        require(NodeTags.matches(spec.nodeTags, node.tags)) { "node ${node.instance} does not match the job's tags" }
        val planned = current.commands.isNotEmpty()
        node.jobs[job.id] = if (planned) "joining job ${job.id}" else "setting up job ${job.id}"
        runner.log(job.id, "${if (planned) "joined" else "taken"} by node ${node.instance} (${node.size.type}, ${node.market})")
        try {
            val (arms, shas) = runner.resolveArms(current, spec, job.log)
            val primary = shas.getValue(arms.first().ref)
            build(node, host, job, shas.values.distinct())
            if (planned) {
                fetchCorpora(host, primary, spec.problems.map { Experiments.selectArgs(it) })
                return
            }
            runner.log(job.id, "${node.instance}: planning, which fetches the corpora the selections read")
            runner.plan(current, spec, arms, shas, primary, job.dir, host)
            store.setup(job.id, primary)
            aws.corpusBucket?.takeUnless { spec.isProfile }?.let { bucket ->
                // Right after planning, before other nodes fetch: they then pull what this one fetched from the bucket
                // instead of each fetching it from its source, and no case is running to slow the upload.
                runner.log(job.id, "${node.instance}: compressing and pushing newly fetched corpora to s3://$bucket")
                // A collection fetched from its source is plain text, many times its compressed size: compressed as the
                // bench stores it, it takes less of the bucket, of every instance's disk and of every sync.
                val bench = "${host.worktree(primary)}/klause-bench"
                val compress = "${AwsHost.ENV}; cd ${quote(bench)} && JAVA_OPTS='-Dklause.workspace.root=${host.worktree(primary)}' " +
                    "./build/install/klause-bench/bin/klause-bench corpus compress ~/corpus > ~/corpus-compress.log 2>&1"
                runCatching { host.ssh.run("${AwsBuildCache.functions}\n$compress; push_lab_corpus $bucket ${aws.region} ~/corpus", S3_PUSH_TIMEOUT_SEC) }
                    .onFailure { runner.log(job.id, "corpus push failed, other nodes fetch upstream: ${it.message}") }
            }
            runner.log(job.id, "planned; the local machine and free nodes may join to share its iterations")
        } catch (e: Exception) {
            if (e is JobCancelled) throw e
            if (runner.crews.hosts(job.id).size > 1 && (store.job(job.id)?.total ?: 0) > 0) throw NotJoined(e)
            throw e
        }
    }

    /** Build each of [shas] the node has not built yet for a job like [job]. */
    private fun build(node: Node, host: AwsHost, job: AwsJob, shas: List<String>) {
        if (job.spec.isProfile) {
            setupProfile(job, host, shas)
            return
        }
        val measuresCli = job.spec.measuresCli
        val missing = shas.filter { !AwsBuildCache.hasBuilt(node.built, it, measuresCli) }
        if (missing.isEmpty()) return
        setup(job, host, missing, node.size.cores - 2 * node.slots.busy)
        missing.forEach { AwsBuildCache.rebuilt(node.built, it, measuresCli) }
        savePool()
    }

    /** [node] as a host for [job]'s cases: its files in their own directory, one case per two cores unless the job
     *  says how many, within the node's slots, which every job on it shares. */
    private fun host(node: Node, job: AwsJob) = AwsHost(
        node.instance, ssh(node) { store.cancelRequested(job.id) }, node.size.cores,
        config.solveJavaOpts, job.spec.measurement, job.spec.isProfile,
        gone = { cli.state(node.instance).takeIf { (state, _) -> state in GONE }?.let { (state, reason) -> "$state, $reason".trimEnd(',', ' ') } },
        onPhase = { index, phase -> store.commandPhase(job.id, index, phase) },
        jobDir = job.remoteDir,
        parallel = if (job.spec.parallel == null) defaultParallel(node.size) else null,
        slots = node.slots,
        jobId = job.id,
        repository = job.spec.repository,
        nodeTags = node.tags,
        cpu = node.cpu,
    ) {
        store.cancelRequested(job.id)
    }

    /** A job a node works on: where its files are, here and on the node. */
    private inner class AwsJob(val id: Long, val spec: ExperimentSpec) {
        val dir: Path = config.createJobDir(id)
        val log: File = dir.resolve("setup.log").toFile().apply { appendText("") }
        val remoteDir = "${AwsHost.HOME}/jobs/$id"
    }

    /** Terminate [node], saying [why], and hand its vCPUs back. It leaves the pool's state file first: should the
     *  terminate fail, or the lab stop before it, the sweep or the next start terminates it as an orphan. */
    private fun retire(node: Node, why: String) {
        nodes.remove(node.instance)
        runCatching { savePool() }
        runCatching { retrying(config.retry) { cli.terminate(listOf(node.instance)) } }
            .onSuccess { println("aws: terminated node ${node.instance}: $why") }
            .onFailure { println("aws: could not terminate node ${node.instance} ($why), the next sweep will: ${it.message}") }
        busy.addAndGet(-node.size.vcpus)
        runCatching { report() }
    }

    /**
     * The startup's recovery, before any launch or sweep: adopt each node of the pool's state file ([PoolState]) that
     * still runs as a lab instance, and terminate every other lab instance. Adoption fails closed: an instance not
     * running, that SSH does not reach within its timeout, or whose stale processes and job files cannot be cleared is
     * terminated, with the reason logged. A missing or unreadable state file adopts nothing, so every lab instance goes.
     * An instance of the file no longer tagged as the lab's is left alone: the lab terminates only its own. The jobs
     * the nodes ran go back to the queue in the runner's recovery ([Runner.recover]), each iteration whole, before any
     * node takes work; an adopted node then takes work as a launched one does.
     */
    internal fun recover() {
        val saved = readPool()
        val up = cli.running().toSet()
        if (saved == null) println("aws: no readable ${POOL_FILE}: no node is adopted, every lab instance is terminated")
        saved?.let { state -> launched.updateAndGet { maxOf(it, state.launched) } }
        val failed = LinkedHashMap<String, String>()
        for (node in saved?.nodes.orEmpty()) {
            if (nodes.containsKey(node.instance)) continue
            if (node.instance !in up) {
                println("aws: node ${node.instance} from before the restart is no longer a running lab instance")
                continue
            }
            runCatching { adopt(node) }.onFailure { failed[node.instance] = it.message ?: it::class.simpleName.orEmpty() }
        }
        poolRecovered = true
        savePool()
        for ((instance, why) in failed) {
            runCatching { retrying(config.retry) { cli.terminate(listOf(instance)) } }
                .onSuccess { println("aws: terminated node $instance, not adopted: $why") }
                .onFailure { println("aws: could not terminate node $instance (not adopted: $why), the next sweep will: ${it.message}") }
        }
        sweep()
    }

    /**
     * Take [saved], an instance of the pool before the restart, back as a node: it must run, answer SSH within
     * [SSH_STEP_SEC] without the long retries of a step, and have every process the previous runner left on it killed
     * and its job files removed ([ADOPT_SCRIPT]); it then takes work as any node. Its age keeps counting from its first
     * launch, so its `maxHours` stands, and so does its own power-off, set at its boot. Fails, for its caller to
     * terminate it, on any doubt.
     */
    private fun adopt(saved: SavedNode) {
        val (state, _) = cli.state(saved.instance)
        check(state == "running") { "it is $state" }
        val ip = runCatching { cli.publicIp(saved.instance) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "None" } ?: saved.ip
        check(ip.isNotEmpty()) { "it has no public address" }
        val node = Node(saved.instance, aws.sizes.firstOrNull { it.type == saved.type } ?: InstanceSize(saved.type, saved.vcpus), saved.market, saved.launchedAt, saved.manual, adopted = true)
        node.ip = ip
        val (exit, out) = ssh(node).exec(ADOPT_SCRIPT, SSH_STEP_SEC, cancellable = false, retries = false)
        check(exit == 0) { "clearing it over SSH failed (exit $exit): ${out.trim().takeLast(ERROR_CHARS)}" }
        val booted = out.lines().any { it.trim() == "ready" }
        if (booted) {
            node.cpu = probe(node)
            node.built.addAll(saved.built)
        }
        node.state = if (booted) "idle" else "booting"
        nodes[node.instance] = node
        busy.addAndGet(node.size.vcpus)
        println("aws: adopted ${node.size.type} ${node.market} node ${node.instance}, launched ${(System.currentTimeMillis() - node.launchedAt) / MS_PER_MIN} min ago")
        thread(isDaemon = true, name = "aws-${node.instance}") { run(node, booted = booted) }
    }

    /** Terminate the lab's instances that are not nodes: ones a node could not terminate when it retired, as when the
     *  lab machine's network was down then, and ones no node of the pool was adopted as after a restart ([recover]).
     *  They would hold the vCPU quota against every launch, and bill. It runs every [sweepMs], and holds off while a
     *  launch is in progress, whose instance is a node only once AWS has given its id. */
    private fun sweep() {
        if (launching.get() > 0) return
        val orphans = cli.running().filter { it !in nodes.keys }
        if (orphans.isNotEmpty()) {
            println("aws: terminating lab instances that are not nodes: $orphans")
            cli.terminate(orphans)
        }
    }

    /** The pool's state file as last written, or null when it is missing or cannot be read. */
    private fun readPool(): PoolState? =
        runCatching { POOL_JSON.decodeFromString(PoolState.serializer(), poolFile.toFile().readText()) }.getOrNull()

    /** Write the pool to its state file, which a restarted worker adopts from ([recover]); not before the startup's
     *  adoption has run, so a failed start keeps the nodes it lists. */
    @Synchronized
    private fun savePool() {
        if (!poolRecovered) return
        val state = PoolState(launched.get(), nodes.values.sortedBy { it.launchedAt }.map {
            SavedNode(it.instance, it.size.type, it.size.vcpus, it.market, it.ip, it.manual, it.launchedAt,
                synchronized(it.built) { it.built.toList() }.sorted())
        })
        poolFile.parent.createDirectories()
        poolFile.writeAtomically(POOL_JSON.encodeToString(PoolState.serializer(), state))
    }

    /** Each instance has the corpora its cases read before they start, so cases that share a collection never fetch
     *  it side by side: from the bucket first, then whatever a selection still lacks. */
    private fun fetchCorpora(host: AwsHost, primary: String, selections: List<String>) {
        aws.corpusBucket?.let { bucket -> runCatching { host.ssh.run("${AwsBuildCache.functions}\npull_lab_corpus $bucket ${aws.region} ~/corpus", S3_PUSH_TIMEOUT_SEC) } }
        selections.forEach { host.select(host.worktree(primary), it) }
    }

    /** The nodes working on [job], one a line (instance, type, market), while it has any: what the pages and `lab ssh`
     *  read. */
    private fun writeInstances(jobId: Long) {
        val file = config.jobDir(jobId).resolve(INSTANCES_FILE)
        val lines = nodes.values.filter { it.jobs.containsKey(jobId) }.sortedBy { it.launchedAt }.map { "${it.instance} ${it.size.type} ${it.market}" }
        if (lines.isEmpty()) Files.deleteIfExists(file) else file.toFile().writeText(lines.joinToString("\n", postfix = "\n"))
    }

    /** The pool as the pages show it, in `aws/nodes.json`. */
    @Synchronized
    private fun report() {
        val report = poolReport(System.currentTimeMillis(), pressure, launching.get(),
            nodes.values.sortedBy { it.launchedAt }.map {
                val jobs = it.jobs.keys.sorted()
                NodeReport(it.instance, it.size.type, it.market, it.doing, jobs.firstOrNull(), it.manual, it.launchedAt, it.adopted, it.tags, it.cpu,
                    jobs = jobs, slots = it.slots.capacity, busySlots = it.slots.busy,
                    free = it.exclusive == null && it.failure == null && it.slots.free() > 0, ordinary = it.ordinary)
            },
            threshold, gate, aws, blocker, runner.updateWaiting(), busy.get(), queueSize?.type, working)
        nodesFile.parent.createDirectories()
        nodesFile.writeAtomically(Json.encodeToString(NodePoolReport.serializer(), report))
    }

    /** Profile builds have their own repository and layout and never use Klause's distribution cache. */
    private fun setupProfile(job: AwsJob, host: AwsHost, shas: List<String>) {
        val script = profileSetup(job.spec, host, shas, config.gradleWorkers, Repositories.source(config, job.spec.repository))
        runner.withRetry(job.id, "building profile on ${host.instance}", config.buildRetry) {
            val (exit, output) = host.ssh.exec(script, BUILD_TIMEOUT_SEC)
            job.log.appendText("[${host.instance}] $output\n")
            check(exit == 0) { "profile build on ${host.instance} failed (exit $exit); see setup.log" }
        }
    }

    private fun nodeArch(tags: Set<String>): String = tags.firstOrNull { it.startsWith("arch:") }?.substringAfter(':') ?: "unknown"

    /**
     * Clone the repository and build each of [shas] on [host], with Gradle on the [cores] the other jobs' running cases
     * leave (one per core, at least one) and at the lowest CPU priority while any run, so a build beside them takes
     * what they leave rather than slow their timed cases.
     */
    private fun setup(job: AwsJob, host: AwsHost, shas: List<String>, cores: Int = host.cores) {
        runner.log(job.id, "${host.instance}: building ${shas.joinToString { it.take(SHA_LOG) }}")
        val workers = cores.coerceIn(1, host.cores)
        val script = buildString {
            appendLine("set -euo pipefail; ${AwsHost.ENV}")
            if (workers < host.cores) appendLine("renice -n 19 -p $$ >/dev/null 2>&1 || true")
            appendLine(AwsBuildCache.functions)
            appendLine("mkdir -p ~/work ~/corpus")
            // The corpus comes down from the bucket while the build runs; a failed sync only means fetching upstream.
            aws.corpusBucket?.let { appendLine("(pull_lab_corpus $it ${aws.region} ~/corpus || true) > ~/corpus-sync.log 2>&1 & sync=${'$'}!") }
            appendLine("[ -d ~/repo/.git ] || git clone -q --filter=blob:none --no-checkout ${quote(Repositories.source(config, Repositories.DEFAULT))} ~/repo")
            appendLine("git -C ~/repo fetch -q origin ${shas.joinToString(" ")} || git -C ~/repo fetch -q origin")
            val bucket = aws.corpusBucket
            // Gradle's caches (dependencies, the wrapper's distribution) come from one archive in the bucket, so a
            // fresh instance downloads nothing from Maven Central or the Gradle site.
            if (bucket != null) {
                appendLine("if aws s3 ls s3://$bucket/$GRADLE_ARCHIVE --region ${aws.region} >/dev/null 2>&1; then")
                appendLine("  restore_lab_cache s3://$bucket/$GRADLE_ARCHIVE ~ gradle ${aws.region} || true")
                appendLine("fi")
            }
            appendLine("built=0")
            for (sha in shas) {
                val worktree = host.worktree(sha)
                appendLine("[ -d $worktree ] || git -C ~/repo worktree add -q --detach $worktree $sha")
                val archives = bucket?.let { AwsBuildCache.archives(it, sha, nodeArch(host.nodeTags)) }.orEmpty()
                AwsBuildCache.worktree(worktree, workers, archives, aws.region).forEach(::appendLine)
                finishWorktree(worktree, job.spec.measuresCli).forEach(::appendLine)
                appendLine("validate_lab_provenance ${quote(worktree)}")
            }
            if (bucket != null) {
                appendLine("if [ ${'$'}built = 1 ]; then publish_lab_cache s3://$bucket/$GRADLE_ARCHIVE ~ ${aws.region} .gradle/caches/modules-2 .gradle/wrapper; fi")
                appendLine("wait ${'$'}sync || true")
            }
        }
        runner.withRetry(job.id, "building on ${host.instance}", config.buildRetry) {
            val (exit, output) = host.ssh.exec(script, BUILD_TIMEOUT_SEC)
            job.log.appendText("[${host.instance}] $output\n")
            check(exit == 0) { "build on ${host.instance} failed (exit $exit); see setup.log" }
        }
        runner.log(job.id, "${host.instance}: built")
    }

    private fun bootstrap(): String = bootstrap(aws)

    companion object {
        /**
         * What a fresh instance runs at first boot: its power-off deadline, its watchdog ([watchdog]), then a JDK,
         * MiniZinc and the tools a build and the bench need, then the mark [boot] waits for. A power-off terminates the
         * instance (`--instance-initiated-shutdown-behavior terminate`).
         */
        internal fun bootstrap(aws: AwsConfig): String = """
        #!/bin/bash
        set -euxo pipefail
        shutdown -h +${aws.maxHours * MINUTES_PER_HOUR}
""".trimIndent() + "\n" + watchdog(aws.nodeWatchdogMin) + """
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -q
        apt-get install -yq git zstd unzip curl xz-utils time jq build-essential libgmp-dev zlib1g-dev
        mkdir -p /opt/jdk /opt/minizinc
        case "${'$'}(uname -m)" in
          x86_64) jdk_arch=x64; minizinc_arch=x86_64; aws_arch=x86_64 ;;
          aarch64|arm64) jdk_arch=aarch64; minizinc_arch=aarch64; aws_arch=aarch64 ;;
          *) echo 'unsupported CPU architecture'; exit 2 ;;
        esac
        curl -fsSL https://api.adoptium.net/v3/binary/latest/25/ga/linux/${'$'}jdk_arch/jdk/hotspot/normal/eclipse | tar -xz -C /opt/jdk --strip-components=1
        if [ "${'$'}minizinc_arch" = x86_64 ]; then
          curl -fsSL https://github.com/MiniZinc/MiniZincIDE/releases/download/${aws.minizinc}/MiniZincIDE-${aws.minizinc}-bundle-linux-x86_64.tgz | tar -xz -C /opt/minizinc --strip-components=1
          ln -sf /opt/minizinc/bin/minizinc /usr/local/bin/minizinc
        else
          apt-get install -yq minizinc
        fi
        curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-${'$'}aws_arch.zip -o /tmp/awscli.zip
        unzip -q /tmp/awscli.zip -d /tmp && /tmp/aws/install
        touch /var/lib/eignex-lab-ready
    """.trimIndent() + "\n"

        /**
         * The node's watchdog, a systemd timer that powers it off once the heartbeat file the lab touches ([HEARTBEAT],
         * [heartbeat]) is older than [minutes]: a lab down, or cut off, leaves no node billing until its `maxHours`.
         * None when [minutes] is 0.
         */
        internal fun watchdog(minutes: Long): String = if (minutes <= 0) "" else """
        touch $HEARTBEAT && chown ubuntu:ubuntu $HEARTBEAT
        cat > /usr/local/sbin/eignex-lab-watchdog <<'EOF'
        #!/bin/bash
        age=${'$'}(( ${'$'}(date +%s) - ${'$'}(stat -c %Y $HEARTBEAT) ))
        if [ "${'$'}age" -gt ${minutes * 60} ]; then
          logger "eignex-lab: no heartbeat from the lab for ${'$'}age s, powering off"
          shutdown -h now
        fi
        EOF
        chmod +x /usr/local/sbin/eignex-lab-watchdog
        printf '[Service]\nType=oneshot\nExecStart=/usr/local/sbin/eignex-lab-watchdog\n' > /etc/systemd/system/eignex-lab-watchdog.service
        printf '[Timer]\nOnBootSec=5min\nOnUnitActiveSec=1min\n[Install]\nWantedBy=timers.target\n' > /etc/systemd/system/eignex-lab-watchdog.timer
        systemctl daemon-reload
        systemctl enable --now eignex-lab-watchdog.timer
""".trimIndent() + "\n"

        /** The file a node's watchdog checks the age of ([watchdog]), which the lab touches. */
        const val HEARTBEAT = "/var/lib/eignex-lab-heartbeat"
        /** Touch the heartbeat; a node launched before the watchdog has no file, and that is no failure. */
        internal const val TOUCH_HEARTBEAT = "touch $HEARTBEAT 2>/dev/null || true"
        private const val READY = "/var/lib/eignex-lab-ready"

        /**
         * What a restarted worker runs on a node it adopts ([adopt]): kill every process the previous runner left there,
         * the SSH sessions it started (those whose session leader sshd started, or whose leader is gone), then remove
         * the job files and check that none survived; exit non-zero when any did. It prints `ready` once the node's
         * bootstrap has finished, else `booting`.
         */
        internal val ADOPT_SCRIPT = """
            u=$(id -un); me=$(ps -o sid= -p $$ | tr -d ' ')
            [ -n "${'$'}me" ] || { echo 'no session'; exit 3; }
            stale() {
              ps -u "${'$'}u" -o pid=,sid=,stat=,comm= | while read -r pid sid stat comm; do
                [ "${'$'}sid" = "${'$'}me" ] && continue
                case "${'$'}stat ${'$'}comm" in Z*|*sshd*) continue;; esac
                pp=$(ps -o ppid= -p "${'$'}sid" 2>/dev/null | tr -d ' ')
                if [ -n "${'$'}pp" ]; then case "$(ps -o comm= -p "${'$'}pp" 2>/dev/null)" in sshd*) ;; *) continue;; esac; fi
                echo "${'$'}pid"
              done
            }
            stale | xargs -r kill -KILL 2>/dev/null
            sleep 1
            left=$(stale | tr '\n' ' ')
            [ -z "${'$'}left" ] || { echo "stale processes survived: ${'$'}left"; exit 4; }
            rm -rf ~/jobs /tmp/eignex-lab-step-* || { echo 'job files not removed'; exit 5; }
            $TOUCH_HEARTBEAT
            if test -f $READY; then echo ready; else echo booting; fi
        """.trimIndent()

        /**
         * The steps after a commit's build is in place in [worktree]: a measurement launcher, then the provenance
         * manifest, which must describe the files the cases will run, launcher included. Both come after the build cache
         * upload, so the cached distributions stay the plain ones.
         */
        internal fun profileSetup(spec: ExperimentSpec, host: AwsHost, shas: List<String>, workers: Int, source: String): String {
            Repositories.requireAllowed(spec.repository)
            val profile = checkNotNull(spec.profile)
            ProfileRuns.validate(spec.repository, profile)
            val repo = "${AwsHost.HOME}/repos/${spec.repository.substringAfter('/').lowercase()}"
            val tasks = ProfileRuns.buildTasks(spec.repository, profile)
            return buildString {
                appendLine("set -euo pipefail; ${AwsHost.ENV}")
                appendLine("mkdir -p ${AwsHost.HOME}/repos ${AwsHost.HOME}/work")
                appendLine("[ -d ${quote("$repo/.git")} ] || git clone -q --filter=blob:none --no-checkout ${quote(source)} ${quote(repo)}")
                appendLine("git -C ${quote(repo)} remote set-url origin ${quote(source)}")
                appendLine("git -C ${quote(repo)} fetch -q origin ${shas.joinToString(" ", transform = ::quote)}")
                for (sha in shas) {
                    val worktree = host.worktree(sha)
                    appendLine("mkdir -p ${quote(worktree.substringBeforeLast('/'))}")
                    appendLine("[ -d ${quote(worktree)} ] || git -C ${quote(repo)} worktree add -q --detach ${quote(worktree)} ${quote(sha)}")
                    if (tasks.isNotEmpty()) appendLine("(cd ${quote(worktree)} && ./gradlew ${tasks.joinToString(" ", transform = ::quote)} --max-workers=$workers -q)")
                }
            }
        }

        internal fun finishWorktree(worktree: String, measuresCli: Boolean): List<String> =
            listOfNotNull(CliMeasurements.install(worktree).takeIf { measuresCli }, Experiments.captureProvenance(worktree))

        /** The pool's report for the pages: its [nodes] and [launching] count, the [pressure] against the [threshold]
         *  for the next node, the [gate]'s state with the settings it runs on and what holds a launch back, and what a
         *  manual start checks: whether an update waits, the vCPUs the nodes hold, and the size the queue calls for. */
        internal fun poolReport(
            now: Long, pressure: Double, launching: Int, nodes: List<NodeReport>, threshold: Double, gate: LaunchGate,
            aws: AwsConfig, blocker: String?, updateWaiting: Boolean = false, busyVcpus: Int = 0, manualSize: String? = null,
            working: Int = nodes.size + launching,
        ) = NodePoolReport(now, pressure, launching, nodes,
            threshold = threshold, nextThreshold = NodePool.nextThreshold(threshold, working),
            overSince = gate.since, lastLaunch = gate.lastLaunch, sustainMs = aws.nodeSustainMin * MS_PER_MIN,
            cooldownMs = aws.nodeCooldownMin * MS_PER_MIN, blocker = blocker, updateWaiting = updateWaiting,
            busyVcpus = busyVcpus, manualSize = manualSize)

        /** Cases a node of [size] runs at once, from every job on it together, and of a job that sets no `parallel`
         *  alone: one per two physical cores ([NodePool.slots]). */
        internal fun defaultParallel(size: InstanceSize): Int = NodePool.slots(size.cores)

        /** The nodes working on a job, one a line, while it has any: what the pages and `lab ssh` show. */
        const val INSTANCES_FILE = "aws-instances"
        /** The pool's state under `$LAB_DATA/aws`, for the pages ([NodePoolReport]). */
        const val NODES_FILE = "nodes.json"
        /** The pool's nodes as a restarted worker adopts them ([PoolState]), under `$LAB_DATA/aws`. */
        const val POOL_FILE = "pool.json"
        private val POOL_JSON = Json { ignoreUnknownKeys = true }
        private const val HEARTBEAT_MS = 5 * 60_000L
        private const val ERROR_CHARS = 500
        const val SPOT = "spot"
        const val ON_DEMAND = "on-demand"

        private const val POLL_MS = 10_000L
        /** How often a node looks for work while it has room. */
        private const val NODE_POLL_MS = 10_000L
        /** The states of an instance that will not run a case again. */
        private val GONE = setOf("shutting-down", "terminated", "stopping", "stopped")
        private const val SWEEP_MS = 5 * 60_000L
        private const val SSH_STEP_SEC = 60L
        private const val SHA_LOG = 9
        /** The errors AWS refuses an instance with for a quota or a lack of capacity, rather than for a fault. */
        private val CAPACITY = Regex("VcpuLimitExceeded|InstanceLimitExceeded|InsufficientInstanceCapacity|MaxSpotInstanceCountExceeded")
        /** The errors AWS refuses every spot launch with until the account is set up for spot, such as a missing
         *  service-linked role the lab's credentials may not create. */
        private val NO_SPOT = Regex("ServiceLinkedRoleCreationNotPermitted")
        private const val S3_PUSH_TIMEOUT_SEC = 3600L
        /** Gradle's dependency cache and wrapper distribution, as one archive in the corpus bucket. */
        private const val GRADLE_ARCHIVE = AwsBuildCache.GRADLE
        private const val BUILD_TIMEOUT_SEC = 3600L
        private const val MINUTES_PER_HOUR = 60
        private const val SPOT_RETRY_MS = 30_000L
        private const val MS_PER_MIN = 60_000L
        private const val MS_PER_SEC = 1000L
        /** A fresh instance takes a few minutes to boot and install: tries every 20 s, up to about 15 minutes. */
        private val BOOT_WAIT = Backoff(attempts = 45, baseMs = 20_000, maxMs = 20_000)
    }
}
