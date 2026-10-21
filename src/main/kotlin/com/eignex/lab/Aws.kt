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
     * demand either way, since a profile is never run on spot.
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
    /** The largest node the pool launches, by hand or not, in vCPUs. Every job is capped to the local machine's cores,
     *  so no case needs more than a node of this size holds; more load is met by more nodes, not larger ones. */
    val nodeMaxVcpus: Int = 16,
) {
    /** The sizes a node can be, smallest first: [instanceType] and the larger sizes of its family up to
     *  [nodeMaxVcpus], within the quota. */
    val sizes: List<InstanceSize>
        get() {
            val family = instanceType.substringBefore('.')
            val smallest = SIZE_VCPUS[instanceType.substringAfter('.')] ?: return listOf(InstanceSize(instanceType, DEFAULT_SIZE_VCPUS))
            return SIZE_VCPUS.entries.filter { (_, vcpus) -> vcpus in smallest..minOf(vcpuQuota, maxOf(nodeMaxVcpus, smallest)) }
                .map { (size, vcpus) -> InstanceSize("$family.$size", vcpus) }
        }

    /** The smallest size with [cores] physical cores for one case, or null when none fits the quota. */
    fun sizeFor(cores: Int): InstanceSize? = sizes.firstOrNull { it.cores >= cores }

    companion object {
        private const val DEFAULT_VCPU_QUOTA = 32
        private const val DEFAULT_SIZE_VCPUS = 8
        /** vCPUs of the compute families' sizes; a physical core is two of them. */
        private val SIZE_VCPUS = linkedMapOf("xlarge" to 4, "2xlarge" to 8, "4xlarge" to 16, "8xlarge" to 32, "12xlarge" to 48, "16xlarge" to 64)

        fun load(file: Path): AwsConfig? {
            if (!file.exists()) return null
            val p = Properties().apply { file.toFile().inputStream().use(::load) }
            fun get(key: String, default: String? = null) = p.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } ?: default
            return AwsConfig(
                region = requireNotNull(get("region")) { "aws.properties needs region" },
                profile = get("profile", "klause-lab")!!,
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
            )
        }
    }
}

/** An instance size: its type and vCPUs; its physical cores are half the vCPUs, each running two hardware threads. */
data class InstanceSize(val type: String, val vcpus: Int) {
    val cores: Int get() = vcpus / 2
}

/** The AWS CLI, as the lab's IAM user, in the background so it stays off the solves' cores. */
class AwsCli(private val aws: AwsConfig) {
    fun call(vararg args: String): String {
        val cmd = Background.prefix + listOf("aws", "--region", aws.region, "--profile", aws.profile, "--output", "text") + args
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "aws ${args.take(2).joinToString(" ")} failed: ${out.take(ERROR_CHARS)}" }
        return out
    }

    fun image(): String = aws.ami ?: call("ssm", "get-parameter", "--name", UBUNTU_IMAGE, "--query", "Parameter.Value")

    /** Launch one node, tagged as the lab's, terminating itself when it powers off. */
    fun launch(name: String, userData: File, type: String, spot: Boolean = false): String = call(
        "ec2", "run-instances",
        // A one-time request that terminates when AWS reclaims the capacity, its price capped at on demand.
        *(if (spot) arrayOf("--instance-market-options", "MarketType=spot,SpotOptions={SpotInstanceType=one-time,InstanceInterruptionBehavior=terminate}") else emptyArray()),
        "--image-id", image(),
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
    fun admitThisMachine() {
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
        const val TAG = "klause-lab"
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
        "-o", "HostKeyAlias=klause-lab-${aws.region}-$instance",
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

    /** Run [script] to its end, at most [timeoutSec]; its exit (255 when SSH itself failed) and its output. */
    /**
     * Run [script] to its end, at most [timeoutSec]; its exit (255 when SSH itself failed) and its output. A connection
     * the lab machine's own network dropped is tried again, backing off to about a quarter of an hour in all: the
     * scripts it runs (a build, a select, a sync) pick up where a cut-off run left them. A refused connection, as from an
     * instance still booting, is returned at once for its caller to wait out.
     */
    fun exec(script: String, timeoutSec: Long): Pair<Int, String> {
        var attempt = 1
        while (true) {
            val (exit, out) = execOnce(script, timeoutSec)
            if (exit != SSH_FAILED || !NETWORK.containsMatchIn(out) || attempt >= NETWORK_RETRY.attempts || cancelled()) return exit to out
            val until = System.currentTimeMillis() + NETWORK_RETRY.delayAfter(attempt)
            while (System.currentTimeMillis() < until && !cancelled()) Thread.sleep(POLL_MS)
            attempt++
        }
    }

    private fun execOnce(script: String, timeoutSec: Long): Pair<Int, String> {
        val process = start(script)
        val output = StringBuilder()
        val reader = thread(isDaemon = true) { output.append(process.inputStream.bufferedReader().readText()) }
        val deadline = System.currentTimeMillis() + timeoutSec * MS_PER_SEC
        while (!process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
            if (cancelled()) {
                process.destroyForcibly()
                error("cancelled")
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
    private val profileCli: Boolean = false,
    /** A profile job: its output and its `$OUT` directory come back whatever its exit. */
    private val profileJob: Boolean = false,
    /** Why the instance is gone, when AWS reports it shut down or terminated; null while it runs. */
    private val gone: () -> String? = { null },
    /** Told each case's [CasePhase] as it changes, for the job's page and its JSON. */
    private val onPhase: (Int, CasePhase) -> Unit = { _, _ -> },
    /** How often and how long a case whose SSH connection dropped is run again. */
    private val retry: Backoff = SSH_RETRY,
    /** Where the job's case files live on the instance: a node runs one job after another, each in its own. */
    private val jobDir: String = "$HOME/job",
    /** The cases at once here, whatever the job's `parallel` says; null follows the job's, up to the cores. */
    private val parallel: Int? = null,
    /** The job's [ExperimentSpec.profileCompilation]: what its profiled CLI records of compilation, and brings back. */
    private val compilation: String? = null,
    private val cancelled: () -> Boolean,
) : ExecutionHost {
    override val corpus = "$HOME/corpus"
    override val yieldsToUpdates = false
    override val yieldsToPriority = false
    override val maxParallel get() = cores

    override fun parallel(requested: Int): Int = parallel ?: super.parallel(requested)

    override fun worktree(sha: String) = "$HOME/work/${sha.take(SHA_DIR)}"

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
        val measurementId = if (profileCli) UUID.randomUUID().toString() else null
        val profile = measurementId?.let { "$jobDir/cases/$i/profile-$it" }
        val options = profile?.let { CliMeasurements.options(it, compilation) }.orEmpty()
        val runtimeOptions = "$solveJavaOpts -XX:ActiveProcessorCount=${command.cores}".trim()
        val javaOptions = "$runtimeOptions $options".trim()
        val script = """
            $ENV
            export JOB_DIR=$jobDir KLAUSE_CLI_OPTS=${quote(javaOptions)} KLAUSE_CLI_PREFLIGHT_OPTS=${quote(runtimeOptions)}
            export OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1 OMP_NUM_THREADS=1
            mkdir -p ${'$'}JOB_DIR/cases
            ${profile?.let { "mkdir -p ${quote(it)}; export KLAUSE_LAB_PROFILE_DIR=${quote(it)}" }.orEmpty()}
            timeout -k 30 ${command.timeoutSec} bash -c ${quote(command.cmd)} > ${'$'}JOB_DIR/$i.out 2> ${'$'}JOB_DIR/$i.err
            e=${'$'}?
            echo "exit=${'$'}e"
            if [ ${'$'}e -eq 137 ] || [ ${'$'}e -eq 143 ]; then
              sudo -n dmesg -T 2>/dev/null | grep -iE 'killed process|out of memory' | tail -n 3 | sed 's/^/$KERNEL/'
            fi
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
                ssh.exec("pkill -f -- '${jobDir.removePrefix("$HOME/")}/cases/$i([^0-9]|${'$'})' || true", KILL_TIMEOUT_SEC)
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
        val keepsOutput = keepsOutput(result, record.isNotEmpty(), profileCli || profileJob)
        if (profileJob || keepsOutput || profile != null) {
            val elapsedMs = runCatching { Json.parseToJsonElement(record).jsonObject["elapsedMs"]?.jsonPrimitive?.longOrNull }.getOrNull()
            report(command, CasePhase.Phase.TRANSFERRING_OUTPUT, attempt, elapsedMs = elapsedMs)
        }
        // Its output, for a case whose record cannot explain what happened: two more SSH round trips each, a cost every
        // case of a job would pay for nothing when it ran cleanly.
        if (profileJob) fetchOutDir(i, dir)
        if (keepsOutput) for (suffix in listOf("out", "err")) {
            runCatching { ssh.download("$jobDir/$i.$suffix", dir.resolve("$i.$suffix"), ARTIFACT_TRANSFER_SEC) }
                .onFailure { error ->
                    dir.resolve("$i.$suffix.transfer-error").toFile()
                        .writeText("${error.message ?: error::class.simpleName}\n")
                }
        }
        if (profile != null) {
            val files = dir.resolve("cases/$i/profile-$measurementId").createDirectories()
            CliMeasurements.writeManifest(files, i, attempt, requireNotNull(measurementId), instance, command.cmd, exit, javaOptions,
                record, macOs = false, compilation = compilation)
            for (name in CliMeasurements.artifacts(compilation)) {
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

        /** Whether a case's stdout and stderr come back from its instance: when it failed, timed out or left no
         *  record, and for every case of a profiling job. */
        internal fun keepsOutput(result: Int, recorded: Boolean, profileCli: Boolean): Boolean =
            profileCli || result != 0 || !recorded

        const val HOME = "/home/ubuntu"
        const val ENV = "export JAVA_HOME=/opt/jdk PATH=/opt/jdk/bin:/usr/local/bin:${'$'}PATH"
        private const val SHA_DIR = 12
        private const val RECORD = "----klause-lab-record----"
        /** Marks the instance's kernel log lines about killed processes, sent back for a case that ended by signal. */
        private const val KERNEL = "kernel-log: "
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

/**
 * Runs AWS work on a pool of nodes: EC2 instances the lab launches when the queue's pressure calls for one
 * ([NodePool], [LaunchGate]), so none runs while nothing waits for AWS. A node, once booted, takes work until there is
 * none for it: it first joins a job already running on AWS that still has problems no node has taken, building that
 * job's commits, and otherwise claims the next queued job it may run. The nodes on one job share its problems through
 * its [ProblemQueue], every arm of a problem on the node that took it, so each comparison is made on one machine.
 * Records come back as cases end, so the job's page, comparisons and CSVs read as a local job's. A node with nothing
 * to take for [AwsConfig.nodeIdleMin] minutes is terminated; one the lab lost track of powers itself off after
 * [AwsConfig.maxHours], and the worker terminates any lab instance that is not one of its nodes.
 */
class AwsWorker(
    private val runner: Runner,
    private val store: Store,
    private val config: Config,
    initial: AwsConfig,
) {
    /** The settings, read again from `aws.properties` on every poll: a raised quota or a new threshold applies to the
     *  next decision without restarting the lab. */
    @Volatile
    private var aws: AwsConfig = initial
    private val cli: AwsCli get() = AwsCli(aws)
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
    /** The jobs running on AWS, each with the nodes working on it. */
    private val jobs = ConcurrentHashMap<Long, AwsJob>()
    /** Held while a node chooses and takes work, and while a job's last node lets it go: two nodes never claim one
     *  job, and none joins a job as it ends. */
    private val claimLock = Any()
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

    /** Whether any node is up or launching: the runner holds off updating itself until none is, since a restart would
     *  end the threads that drive them. */
    val active: Boolean get() = busy.get() > 0 || launching.get() > 0
    private val knownHosts = config.dataDir.resolve("aws").resolve("known_hosts")
    private val nodesFile = config.dataDir.resolve("aws").resolve(NODES_FILE)

    fun loop() {
        knownHosts.parent.createDirectories()
        runCatching { recover() }.onFailure { println("aws: recovery failed: ${it.message}") }
        var swept = System.currentTimeMillis()
        while (true) {
            runCatching { AwsConfig.load(settings)?.let { aws = it } }.onFailure { println("aws: settings not reread: ${it.message}") }
            runCatching { scale() }.onFailure { println("aws: ${it.message}") }
            runCatching { manual() }.onFailure { println("aws: manual launch failed: ${it.message}") }
            runCatching { report() }
            if (System.currentTimeMillis() - swept > SWEEP_MS) {
                swept = System.currentTimeMillis()
                runCatching { sweep() }.onFailure { println("aws: sweep failed: ${it.message}") }
            }
            Thread.sleep(POLL_MS)
        }
    }

    /**
     * The work waiting for AWS, as [Demand]s: the queued jobs a node may take, in queue order, and the unclaimed
     * problems of the jobs running on AWS. A queued job the local machine could run counts only at
     * [AwsConfig.nodeMinPriority] or above, and the one the local machine takes next, being free or about to give way
     * to it ([Store.localNext]), is left to it; one only AWS can run always counts. A queued AWS job no size within the
     * quota can hold fails here, as no node could ever take it.
     */
    private fun demands(): Pair<List<Demand>, List<Demand>> {
        val localNext = store.localNext()
        val queued = store.nodeQueue().mapNotNull { job ->
            val spec = job.experiment ?: return@mapNotNull null
            if (job.id == localNext) return@mapNotNull null
            val awsOnly = spec.host == Experiments.AWS_HOST
            if (!awsOnly && job.priority < aws.nodeMinPriority) return@mapNotNull null
            val cores = Experiments.arms(spec).maxOf { it.cores }
            if (aws.sizeFor(cores) == null) {
                if (awsOnly) store.claimForNode(job.id)?.let {
                    store.finish(job.id, Status.FAILED, "its cases need $cores cores, more than an instance within the ${aws.vcpuQuota}-vCPU quota has")
                }
                return@mapNotNull null
            }
            Demand(job.id, Estimates.pressureCoreHours(job, history::lookup, config.cores), cores, spec.isProfile, job.priority)
        }
        val running = jobs.values.mapNotNull { it.demand() }
        return queued to running
    }

    /** The pressure, in core-hours, that launches the first node: [AwsConfig.nodeLaunchLocalHours] of the local
     *  machine's cores, so a node launches only for a queue that would keep the local machine busy that long, never for
     *  a job or two. */
    private val threshold: Double get() = NodePool.threshold(config.cores, aws.nodeLaunchLocalHours)

    /** Launch a node when the pressure calls for one ([LaunchGate]), sized to what waits ([NodePool.size]). */
    private fun scale() {
        val (queued, running) = demands()
        val all = queued + running
        pressure = all.sumOf { it.coreHours }
        // A waiting update needs every node gone; launching another would hold it back indefinitely.
        val count = nodes.size + launching.get()
        val size = all.maxOfOrNull { it.cores }?.let { cores ->
            NodePool.size(aws.sizes, cores, pressure, count, aws.vcpuQuota - busy.get(), aws.nodeMaxVcpus)
        }
        queueSize = size ?: NodePool.size(aws.sizes, 1, 0.0, count, aws.vcpuQuota - busy.get(), aws.nodeMaxVcpus).takeIf { all.isEmpty() }
        val noRoom = when {
            count >= aws.maxInstances -> LaunchGate.AT_MAX
            runner.updateWaiting() -> LaunchGate.UPDATE_WAITING
            size == null && all.isNotEmpty() -> LaunchGate.QUOTA_FULL
            else -> null
        }
        val now = System.currentTimeMillis()
        val sustainMs = aws.nodeSustainMin * MS_PER_MIN
        val cooldownMs = aws.nodeCooldownMin * MS_PER_MIN
        val launch = gate.shouldLaunch(now, pressure, count, threshold, size != null && noRoom == null, sustainMs, cooldownMs)
        blocker = if (launch) null else gate.blocker(now, pressure, count, threshold, noRoom, sustainMs, cooldownMs)
        if (!launch || size == null) return
        // A profile job runs only on an on-demand node: one is bought for it when none is up or on its way.
        val onDemand = queued.any { it.profile } && nodes.values.none { it.onDemand } && launchingOnDemand.get() == 0
        println("aws: pressure %.1f core-hours on $count nodes: launching a %s %s node".format(pressure, size.type, if (onDemand) "on-demand" else "spot-first"))
        launch(size, onDemand)
    }

    /**
     * Start the node a manual launch asked for ([ManualLaunch]), when its request waits: past the pressure threshold
     * and the sustain wait, but within `maxInstances`, the quota and a waiting update, checked again here against the
     * pool as it now stands. It counts as the gate's last launch, for the cooldown.
     */
    private fun manual() {
        val request = ManualLaunch.take(manualFile) ?: return
        val now = System.currentTimeMillis()
        val plan = ManualLaunch.decide(request, now, aws, queueSize, nodes.size + launching.get(), aws.vcpuQuota - busy.get(),
            runner.updateWaiting()).getOrElse {
            println("aws: manual launch refused: ${it.message}")
            return
        }
        gate.manualLaunch(now)
        println("aws: manual launch of a ${plan.size.type} ${ManualLaunch.marketText(plan.onDemand)} node, asked from the ${request.by ?: "unknown"}")
        launch(plan.size, plan.onDemand, manual = true)
        report()
    }

    /** Reserve [size]'s vCPUs and launch a node on its own thread, which then runs it until it retires. */
    private fun launch(size: InstanceSize, onDemand: Boolean, manual: Boolean = false) {
        busy.addAndGet(size.vcpus)
        launching.incrementAndGet()
        if (onDemand) launchingOnDemand.incrementAndGet()
        thread(name = "aws-node-${launched.get() + 1}") {
            val node = try {
                buy(size, onDemand, manual)
            } catch (e: Exception) {
                println("aws: no ${size.type} node launched: ${e.message}")
                null
            } finally {
                launching.decrementAndGet()
                if (onDemand) launchingOnDemand.decrementAndGet()
            }
            if (node == null) busy.addAndGet(-size.vcpus) else run(node)
        }
    }

    /** One instance of [size], registered as a node at once so the sweep leaves it alone; [manual] when started by
     *  hand. */
    private fun buy(size: InstanceSize, onDemand: Boolean, manual: Boolean): Node {
        runCatching { cli.admitThisMachine() }
        val userData = Files.createTempFile("klause-lab-user-data", ".sh").toFile()
        try {
            userData.writeText(bootstrap())
            val name = "klause-lab-node-${launched.incrementAndGet()}"
            val (id, market) = if (onDemand) cli.launch(name, userData, size.type) to ON_DEMAND else spotFirst(name, userData, size.type, manual)
            return Node(id, size, market, System.currentTimeMillis(), manual).also { nodes[id] = it }
                .also { println("aws: launched ${size.type} $market ${if (manual) "manual " else ""}node $id") }
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
    private fun spotFirst(name: String, userData: File, type: String, manual: Boolean = false): Pair<String, String> {
        val since = System.currentTimeMillis()
        while (true) {
            val e = runCatching { return cli.launch(name, userData, type, spot = true) to SPOT }.exceptionOrNull()!!
            val message = e.message.orEmpty()
            val capacity = CAPACITY.find(message)?.value
            val waited = System.currentTimeMillis() - since
            if (aws.onDemand && (NO_SPOT.containsMatchIn(message) || capacity != null && waited >= aws.spotWaitMin * MS_PER_MIN)) {
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

    /** An instance of the pool: its size, how it was bought, whether it was started by hand, and what it is doing, for
     *  the pages. */
    private inner class Node(val instance: String, val size: InstanceSize, val market: String, val launchedAt: Long, val manual: Boolean = false) {
        val onDemand: Boolean get() = market == ON_DEMAND
        val shape: NodeShape get() = NodeShape(size.cores, onDemand)
        @Volatile
        var doing = "booting"
        @Volatile
        var job: Long? = null
        @Volatile
        var ip = ""
        /** The commits built on it, each keyed with whether a profiling launcher was installed for it. */
        val built: MutableSet<String> = Collections.synchronizedSet(HashSet())
    }

    /** A node's life: boot, then take work until [NodePool.retire] says it is done, then terminate it. */
    private fun run(node: Node) {
        try {
            boot(node)
            var idleSince: Long? = System.currentTimeMillis()
            while (true) {
                val why = NodePool.retire(System.currentTimeMillis(), node.launchedAt, idleSince, aws.nodeIdleMin * MS_PER_MIN,
                    NodePool.maxAgeMs(aws.maxHours), runner.updateWaiting())
                if (why != null) {
                    println("aws: node ${node.instance} $why")
                    break
                }
                val job = take(node)
                if (job == null) {
                    node.doing = "idle"
                    Thread.sleep(NODE_POLL_MS)
                    continue
                }
                // A node whose work failed may be the reason; it retires rather than take the next job down with it.
                if (!work(node, job)) break
                idleSince = System.currentTimeMillis()
                node.doing = "idle"
            }
        } catch (e: Exception) {
            println("aws: node ${node.instance} failed: ${e.message}")
        } finally {
            retire(node)
        }
    }

    private fun boot(node: Node) {
        runner.withRetry(null, "waiting for ${node.instance}") { cli.awaitRunning(node.instance) }
        node.ip = runner.withRetry(null, "reading ${node.instance}'s address") {
            cli.publicIp(node.instance).also { check(it != "None") { "no public address yet" } }
        }
        val ssh = Ssh(node.instance, node.ip, aws, knownHosts)
        runner.withRetry(null, "waiting for ${node.instance} to boot", BOOT_WAIT) { ssh.run("test -f /var/lib/klause-ready", SSH_STEP_SEC) }
        node.doing = "idle"
        report()
    }

    /** The job [node] works on next, joined or claimed ([NodePool.pick]); null when none is for it. While an update
     *  waits, a node only joins jobs already running, so they drain. */
    private fun take(node: Node): AwsJob? = synchronized(claimLock) {
        val (queued, running) = demands()
        val work = NodePool.pick(node.shape, running, if (runner.updateWaiting()) emptyList() else queued) ?: return null
        val job = when (work) {
            is NodeWork.Help -> jobs[work.jobId] ?: return null
            is NodeWork.Claim -> {
                val claimed = store.claimForNode(work.jobId) ?: return null
                AwsJob(claimed.id, checkNotNull(claimed.experiment), node).also { jobs[claimed.id] = it }
            }
        }
        job.workers += node
        node.job = job.id
        writeInstances(job)
        job
    }

    /**
     * Work on [job] on [node]: set it up (the node that claimed it plans it), then run its cases until no problem is
     * left for this node. False when the node should retire: its work failed, or AWS took it back.
     */
    private fun work(node: Node, job: AwsJob): Boolean {
        val host = host(node, job)
        var healthy = false
        try {
            if (job.claimedBy === node) start(node, host, job) else join(node, host, job)
            node.doing = "running job ${job.id}"
            report()
            val problems = checkNotNull(job.problems)
            // Past its age a node takes no more problems, so it retires before it powers itself off mid-case.
            val maxAge = NodePool.maxAgeMs(aws.maxHours)
            val next = { if (System.currentTimeMillis() - node.launchedAt >= maxAge) null else problems.take() }
            val outcome = runner.dispatch(checkNotNull(store.job(job.id)), job.dir, host, next) { held -> problems.giveBack(held) }
            job.outcomes += outcome
            if (outcome == Dispatched.LOST) runner.log(job.id, "node ${node.instance} was taken back by the cloud provider")
            healthy = outcome != Dispatched.LOST
        } catch (e: NotJoined) {
            runner.log(job.id, "node ${node.instance} did not join: ${e.message}")
        } catch (e: Exception) {
            // The job's own setup, or a case failing to run, fails the job; its other nodes take no more of it.
            if (!store.cancelRequested(job.id)) job.failure = job.failure ?: e
            job.problems?.clear()
        } finally {
            leave(node, job)
        }
        // Its case files came back as each case ended; the next job on this node starts from an empty directory.
        if (healthy) runCatching { host.ssh.exec("rm -rf ${quote(job.remoteDir)}", SSH_STEP_SEC) }
        return healthy
    }

    /** A helper's setup failing: the job goes on without it. */
    private class NotJoined(cause: Throwable) : Exception(cause.message, cause)

    /** The claiming node's setup: resolve and build the job's commits, plan it if it never was, then open its problems
     *  to every node. */
    private fun start(node: Node, host: AwsHost, job: AwsJob) {
        node.doing = "setting up job ${job.id}"
        val current = checkNotNull(store.job(job.id))
        val spec = job.spec
        runner.log(job.id, "taken by node ${node.instance} (${node.size.type}, ${node.market})")
        val (arms, shas) = runner.resolveArms(current, spec, job.log)
        val primary = shas.getValue(arms.first().ref)
        build(node, host, job, shas.values.distinct())
        val selections = spec.problems.map { Experiments.selectArgs(it) }
        if (current.commands.isEmpty()) {
            runner.log(job.id, "${node.instance}: planning, which fetches the corpora the selections read")
            runner.plan(current, spec, arms, shas, primary, job.dir, host)
            aws.corpusBucket?.let { bucket ->
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
        } else {
            fetchCorpora(host, primary, selections)
            runner.adopt(current, spec, host)
        }
        store.setup(job.id, primary)
        // Unset, a job runs one case per two physical cores of each node ([AwsHost]'s limit); this is what the pages show.
        if (spec.parallel == null) store.setParallel(job.id, defaultParallel(node.size))
        job.setup = JobSetup(shas.values.distinct(), primary, selections)
        job.problems = ProblemQueue(store.cases(job.id))
        runner.log(job.id, "running ${job.problems?.size} problems; free nodes join to share them")
    }

    /** A node joining a job that is running: build its commits and fetch its corpora, then take problems too. */
    private fun join(node: Node, host: AwsHost, job: AwsJob) {
        node.doing = "joining job ${job.id}"
        val setup = checkNotNull(job.setup)
        try {
            build(node, host, job, setup.shas)
            fetchCorpora(host, setup.primary, setup.selections)
        } catch (e: Exception) {
            throw NotJoined(e)
        }
        runner.log(job.id, "node ${node.instance} (${node.size.type}, ${node.market}) joined")
    }

    /** Build each of [shas] the node has not built yet for a job like [job]. */
    private fun build(node: Node, host: AwsHost, job: AwsJob, shas: List<String>) {
        val missing = shas.filter { "$it:${job.spec.profileCli}" !in node.built }
        if (missing.isEmpty()) return
        setup(job, host, missing)
        missing.forEach { node.built += "$it:${job.spec.profileCli}" }
    }

    /** [node] is done with [job]; the job's last node ends it as its nodes' outcomes say. */
    private fun leave(node: Node, job: AwsJob) = synchronized(claimLock) {
        job.workers -= node
        node.job = null
        writeInstances(job)
        if (job.workers.isNotEmpty()) return@synchronized
        jobs.remove(job.id)
        runCatching { end(job) }.onFailure { println("aws: ending job ${job.id} failed: ${it.message}") }
    }

    private fun end(job: AwsJob) {
        val current = store.job(job.id) ?: return
        val failure = job.failure
        when {
            store.cancelRequested(job.id) || Dispatched.CANCELLED in job.outcomes -> runner.cancel(job.id)
            failure != null -> {
                runner.log(job.id, "job failed: ${failure.message}")
                store.cancelRemaining(job.id)
                store.finish(job.id, Status.FAILED, failure.message ?: failure.toString())
            }
            current.paused || Dispatched.YIELDED in job.outcomes ->
                requeue(job.id, "job paused between commands; taken up again on resume")
            // Its nodes were taken back by AWS or retired with cases left: the next free node takes it up again.
            current.commands.any { it.status == Status.QUEUED } ->
                requeue(job.id, "no node left on the job with cases to run: queued again")
            else -> {
                store.finish(job.id, Status.DONE)
                runner.log(job.id, "job finished")
            }
        }
    }

    /**
     * Put [jobId] back in the queue, saying [why], and place it again ([Placement.release]): the local machine may then
     * take it over, before a node, as it may any job. One whose cancel was requested meanwhile ends cancelled instead.
     */
    private fun requeue(jobId: Long, why: String) {
        if (store.requeue(jobId) == Status.CANCELLED) return runner.cancel(jobId)
        runner.log(jobId, why)
        if (Placement.release(store, config, jobId)) runner.log(jobId, "placed again: locally when the local machine is free, else a cloud node")
    }

    /** [node] as a host for [job]'s cases: its files in their own directory, one case per two cores unless the job
     *  says how many. */
    private fun host(node: Node, job: AwsJob) = AwsHost(
        node.instance, Ssh(node.instance, node.ip, aws, knownHosts) { store.cancelRequested(job.id) }, node.size.cores,
        config.solveJavaOpts, job.spec.profileCli, job.spec.isProfile,
        gone = { cli.state(node.instance).takeIf { (state, _) -> state in GONE }?.let { (state, reason) -> "$state, $reason".trimEnd(',', ' ') } },
        onPhase = { index, phase -> store.commandPhase(job.id, index, phase) },
        jobDir = job.remoteDir,
        parallel = if (job.spec.parallel == null) defaultParallel(node.size) else null,
        compilation = job.spec.profileCompilation,
    ) {
        store.cancelRequested(job.id)
    }

    /** What a node needs of a job it joins, once the node that claimed it has set it up. */
    private data class JobSetup(val shas: List<String>, val primary: String, val selections: List<String>)

    /** A job running on AWS: the nodes on it, its problems once set up, and how each node's part of it ended. */
    private inner class AwsJob(val id: Long, val spec: ExperimentSpec, val claimedBy: Node) {
        val dir: Path = config.jobDir(id).createDirectories()
        val log: File = dir.resolve("setup.log").toFile().apply { appendText("") }
        val remoteDir = "${AwsHost.HOME}/jobs/$id"
        /** Guarded by [claimLock]. */
        val workers = LinkedHashSet<Node>()
        val outcomes: MutableList<Dispatched> = Collections.synchronizedList(ArrayList())
        @Volatile
        var setup: JobSetup? = null
        @Volatile
        var problems: ProblemQueue? = null
        @Volatile
        var failure: Throwable? = null

        /** Its unclaimed work, while another node could join it. */
        fun demand(): Demand? {
            val queue = problems?.takeIf { !it.isEmpty() && failure == null } ?: return null
            val current = store.job(id)?.takeIf { !it.paused && !it.cancelRequested } ?: return null
            return Demand(id, queue.cases * Estimates.caseCoreHours(current, history::lookup), Experiments.arms(spec).maxOf { it.cores },
                spec.isProfile, current.priority)
        }
    }

    /** Terminate [node] and hand its vCPUs back. */
    private fun retire(node: Node) {
        nodes.remove(node.instance)
        runCatching { retrying(config.retry) { cli.terminate(listOf(node.instance)) } }
            .onSuccess { println("aws: terminated node ${node.instance}") }
            .onFailure { println("aws: could not terminate node ${node.instance}, the next sweep will: ${it.message}") }
        busy.addAndGet(-node.size.vcpus)
        runCatching { report() }
    }

    /** After a restart: jobs left running on AWS go back in the queue, and every lab instance, no node now, goes. */
    private fun recover() {
        for (job in store.active().filter { it.status == Status.RUNNING && it.experiment?.host == Experiments.AWS_HOST }) {
            store.requeueInterrupted(job.id)
            Files.deleteIfExists(config.jobDir(job.id).resolve(INSTANCES_FILE))
            requeue(job.id, "the lab restarted while the job ran on a cloud node: queued again")
        }
        sweep()
    }

    /** Terminate the lab's instances that are not nodes: ones a node could not terminate when it retired, as when the
     *  lab machine's network was down then. They would hold the vCPU quota against every launch. */
    private fun sweep() {
        if (launching.get() > 0) return
        val orphans = cli.running().filter { it !in nodes.keys }
        if (orphans.isNotEmpty()) {
            println("aws: terminating instances that are not nodes: $orphans")
            cli.terminate(orphans)
        }
    }

    /** Each instance has the corpora its cases read before they start, so cases that share a collection never fetch
     *  it side by side: from the bucket first, then whatever a selection still lacks. */
    private fun fetchCorpora(host: AwsHost, primary: String, selections: List<String>) {
        aws.corpusBucket?.let { bucket -> runCatching { host.ssh.run("${AwsBuildCache.functions}\npull_lab_corpus $bucket ${aws.region} ~/corpus", S3_PUSH_TIMEOUT_SEC) } }
        selections.forEach { host.select(host.worktree(primary), it) }
    }

    /** The nodes working on [job], one a line (instance, type, market), while it has any: what the pages and `lab ssh`
     *  read. */
    private fun writeInstances(job: AwsJob) {
        val file = config.jobDir(job.id).resolve(INSTANCES_FILE)
        val lines = job.workers.map { "${it.instance} ${it.size.type} ${it.market}" }
        if (lines.isEmpty()) Files.deleteIfExists(file) else file.toFile().writeText(lines.joinToString("\n", postfix = "\n"))
    }

    /** The pool as the pages show it, in `aws/nodes.json`. */
    @Synchronized
    private fun report() {
        val report = poolReport(System.currentTimeMillis(), pressure, launching.get(),
            nodes.values.sortedBy { it.launchedAt }.map { NodeReport(it.instance, it.size.type, it.market, it.doing, it.job, it.manual) },
            threshold, gate, aws, blocker, runner.updateWaiting(), busy.get(), queueSize?.type)
        nodesFile.parent.createDirectories()
        nodesFile.writeAtomically(Json.encodeToString(NodePoolReport.serializer(), report))
    }

    /** Clone the repository and build each of [shas] on [host]. */
    private fun setup(job: AwsJob, host: AwsHost, shas: List<String>) {
        runner.log(job.id, "${host.instance}: building ${shas.joinToString { it.take(SHA_LOG) }}")
        val script = buildString {
            appendLine("set -euo pipefail; ${AwsHost.ENV}")
            appendLine(AwsBuildCache.functions)
            appendLine("mkdir -p ~/work ~/corpus")
            // The corpus comes down from the bucket while the build runs; a failed sync only means fetching upstream.
            aws.corpusBucket?.let { appendLine("(pull_lab_corpus $it ${aws.region} ~/corpus || true) > ~/corpus-sync.log 2>&1 & sync=${'$'}!") }
            appendLine("[ -d ~/repo/.git ] || git clone -q --filter=blob:none --no-checkout ${quote(config.repoUrl)} ~/repo")
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
                val archive = bucket?.let { "s3://$it/${AwsBuildCache.BUILDS}/$sha.tar.zst" }
                AwsBuildCache.worktree(worktree, host.cores, archive, aws.region).forEach(::appendLine)
                finishWorktree(worktree, job.spec.profileCli).forEach(::appendLine)
                appendLine("validate_lab_provenance ${quote(worktree)}")
            }
            if (bucket != null) {
                appendLine("if [ ${'$'}built = 1 ]; then publish_lab_cache s3://$bucket/$GRADLE_ARCHIVE ~ ${aws.region} .gradle/caches/modules-2 .gradle/wrapper; fi")
                appendLine("wait ${'$'}sync || true")
            }
        }
        runner.withRetry(job.id, "building on ${host.instance}", config.buildRetry) {
            val (exit, out) = host.ssh.exec(script, BUILD_TIMEOUT_SEC)
            job.log.appendText("[${host.instance}] $out\n")
            check(exit == 0) { "build on ${host.instance} failed (exit $exit); see setup.log" }
        }
        runner.log(job.id, "${host.instance}: built")
    }

    /** What a fresh instance runs at first boot: its power-off deadline, then a JDK, MiniZinc and the tools a build and
     *  the bench need, then the mark [boot] waits for. */
    private fun bootstrap(): String = """
        #!/bin/bash
        set -euxo pipefail
        shutdown -h +${aws.maxHours * MINUTES_PER_HOUR}
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -q
        apt-get install -yq git zstd unzip curl xz-utils time jq build-essential libgmp-dev zlib1g-dev
        mkdir -p /opt/jdk /opt/minizinc
        curl -fsSL https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse | tar -xz -C /opt/jdk --strip-components=1
        curl -fsSL https://github.com/MiniZinc/MiniZincIDE/releases/download/${aws.minizinc}/MiniZincIDE-${aws.minizinc}-bundle-linux-x86_64.tgz | tar -xz -C /opt/minizinc --strip-components=1
        ln -sf /opt/minizinc/bin/minizinc /usr/local/bin/minizinc
        curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o /tmp/awscli.zip
        unzip -q /tmp/awscli.zip -d /tmp && /tmp/aws/install
        touch /var/lib/klause-ready
    """.trimIndent() + "\n"

    companion object {
        /**
         * The steps after a commit's build is in place in [worktree]: a profiling job's launcher, then the provenance
         * manifest, which must describe the files the cases will run, launcher included. Both come after the build cache
         * upload, so the cached distributions stay the plain ones.
         */
        internal fun finishWorktree(worktree: String, profileCli: Boolean): List<String> =
            listOfNotNull(CliMeasurements.install(worktree).takeIf { profileCli }, Experiments.captureProvenance(worktree))

        /** The pool's report for the pages: its [nodes] and [launching] count, the [pressure] against the [threshold]
         *  for the next node, the [gate]'s state with the settings it runs on and what holds a launch back, and what a
         *  manual start checks: whether an update waits, the vCPUs the nodes hold, and the size the queue calls for. */
        internal fun poolReport(
            now: Long, pressure: Double, launching: Int, nodes: List<NodeReport>, threshold: Double, gate: LaunchGate,
            aws: AwsConfig, blocker: String?, updateWaiting: Boolean = false, busyVcpus: Int = 0, manualSize: String? = null,
        ) = NodePoolReport(now, pressure, launching, nodes,
            threshold = threshold, nextThreshold = NodePool.nextThreshold(threshold, nodes.size + launching),
            overSince = gate.since, lastLaunch = gate.lastLaunch, sustainMs = aws.nodeSustainMin * MS_PER_MIN,
            cooldownMs = aws.nodeCooldownMin * MS_PER_MIN, blocker = blocker, updateWaiting = updateWaiting,
            busyVcpus = busyVcpus, manualSize = manualSize)

        /** Cases a node of [size] runs at once when a job sets no `parallel`: one per two physical cores. */
        internal fun defaultParallel(size: InstanceSize): Int = (size.cores / 2).coerceAtLeast(1)

        /** The nodes working on a job, one a line, while it has any: what the pages and `lab ssh` show. */
        const val INSTANCES_FILE = "aws-instances"
        /** The pool's state under `$LAB_DATA/aws`, for the pages ([NodePoolReport]). */
        const val NODES_FILE = "nodes.json"
        const val SPOT = "spot"
        const val ON_DEMAND = "on-demand"

        private const val POLL_MS = 10_000L
        /** How often an idle node looks for work. */
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

/** A job's problems not yet taken by an instance, in plan order, each as the indices of its cases: every arm, seed and
 *  repeat of a problem goes to the instance that takes it, so each comparison is made on one machine. */
internal class ProblemQueue(cases: List<CaseResult>) {
    private val left = ArrayDeque(cases.filter { it.status == Status.QUEUED }
        .groupBy { it.problem.suite to it.problem.problem }.values.map { group -> group.map { it.index } })

    val size: Int @Synchronized get() = left.size

    /** The cases of the problems left: the work no node has taken, which the pool's pressure counts. */
    val cases: Int @Synchronized get() = left.sumOf { it.size }

    @Synchronized
    fun take(): List<Int>? = left.removeFirstOrNull()

    @Synchronized
    fun isEmpty(): Boolean = left.isEmpty()

    /** Drop every problem left: the job failed, and its nodes take no more of it. */
    @Synchronized
    fun clear() = left.clear()

    /** Cases a lost host held, back at the front: the next host with room takes them first. */
    @Synchronized
    fun giveBack(indices: Collection<Int>) {
        if (indices.isNotEmpty()) left.addFirst(indices.sorted())
    }
}
