package com.eignex.lab

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
    /** The most instances one job splits over by default. */
    val maxInstances: Int,
    /** The account's on-demand vCPU quota for the family: what every running instance together may hold. */
    val vcpuQuota: Int = DEFAULT_VCPU_QUOTA,
    val keyName: String,
    /** The private key of [keyName], on the lab machine. */
    val keyFile: String,
    val securityGroup: String,
    val subnet: String?,
    /** Cores of one instance a job's cases may hold together: its physical cores, as the lab Mac's are its P-cores. */
    val cores: Int,
    /** An instance powers itself off after this long, whatever the lab does: a lost controller cannot leave one running. */
    val maxHours: Int,
    /** The MiniZinc bundle an instance installs, the lab Mac's version so models compile alike. */
    val minizinc: String,
    /** An image to launch instead of the current Ubuntu 24.04 one. */
    val ami: String?,
    /** An S3 bucket holding a copy of the corpus cache: an instance syncs it down while it builds and pushes back what
     *  it had to fetch, so each collection is fetched from its source once. Null: every instance fetches its own. */
    val corpusBucket: String? = null,
    /** The instance profile whose role may read and write [corpusBucket]. */
    val instanceProfile: String? = null,
    /** Launch instances as spot capacity, far cheaper and reclaimable at two minutes' notice ([AwsWorker] requeues
     *  a reclaimed instance's cases), unless this is false: on demand. */
    val spot: Boolean = true,
    /** When AWS has no spot capacity of the type or quota for it, launch that instance on demand rather than wait. */
    val spotFallback: Boolean = true,
) {
    /** The sizes a job can get, smallest first: [instanceType] and the larger sizes of its family the quota allows. */
    val sizes: List<InstanceSize>
        get() {
            val family = instanceType.substringBefore('.')
            val smallest = SIZE_VCPUS[instanceType.substringAfter('.')] ?: return listOf(InstanceSize(instanceType, DEFAULT_SIZE_VCPUS))
            return SIZE_VCPUS.entries.filter { (_, vcpus) -> vcpus in smallest..vcpuQuota }
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
                spot = get("market", "spot") != "on-demand",
                spotFallback = get("spotFallback", "true")!!.toBoolean(),
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

    /** Launch one instance for job [jobId], tagged as the lab's, terminating itself when it powers off. */
    fun launch(jobId: Long, name: String, userData: File, type: String, spot: Boolean = false): String = call(
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
        "ResourceType=instance,Tags=[{Key=$TAG,Value=true},{Key=Name,Value=$name},{Key=$JOB_TAG,Value=$jobId}]",
        "ResourceType=volume,Tags=[{Key=$TAG,Value=true},{Key=$JOB_TAG,Value=$jobId}]",
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

    /** The lab's instances that are pending or running, each with the job it was launched for. */
    fun running(): List<Pair<String, Long?>> = call(
        "ec2", "describe-instances",
        "--filters", "Name=tag:$TAG,Values=true", "Name=instance-state-name,Values=pending,running",
        "--query", "Reservations[].Instances[].[InstanceId, Tags[?Key=='$JOB_TAG']|[0].Value]",
    ).lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
        val parts = line.split(Regex("\\s+"))
        parts[0] to parts.getOrNull(1)?.toLongOrNull()
    }

    private companion object {
        const val UBUNTU_IMAGE = "/aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id"
        const val TAG = "klause-lab"
        const val JOB_TAG = "klause-lab-job"
        const val VOLUME_GB = 100
        const val ERROR_CHARS = 2000
    }
}

/** An instance as the lab reaches it: SSH as `ubuntu`, with the lab's key, in the background. */
class Ssh(
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
    fun start(script: String, mergeErrors: Boolean = true): Process = ProcessBuilder(command(script))
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
        var attempt = 1
        while (true) {
            try {
                receiveFile(start("cat -- ${quote(remote)}", mergeErrors = false), destination, timeoutSec, cancelled)
                return
            } catch (error: FileTransferFailure) {
                if ((error.exit != null && error.exit != SSH_FAILED) || attempt >= NETWORK_RETRY.attempts || cancelled()) {
                    throw error
                }
                val until = System.currentTimeMillis() + NETWORK_RETRY.delayAfter(attempt)
                while (System.currentTimeMillis() < until && !cancelled()) Thread.sleep(POLL_MS)
                attempt++
            }
        }
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
    /** A script job: its output and its `$OUT` directory come back whatever its exit. */
    private val scriptJob: Boolean = false,
    /** Why the instance is gone, when AWS reports it shut down or terminated; null while it runs. */
    private val gone: () -> String? = { null },
    private val cancelled: () -> Boolean,
) : ExecutionHost {
    override val corpus = "$HOME/corpus"
    override val yieldsToUpdates = false
    override val yieldsToPriority = false
    override val maxParallel get() = cores

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
                runCatching(gone).getOrNull()?.let { reason -> throw HostLost("$instance is gone ($reason)") }
                if (attempt >= SSH_RETRY.attempts || cancelled()) throw IllegalStateException(e.message, e)
                val until = System.currentTimeMillis() + SSH_RETRY.delayAfter(attempt)
                while (System.currentTimeMillis() < until && !cancelled()) Thread.sleep(POLL_MS)
                attempt++
            }
        }
    }

    private class SshDropped(message: String) : Exception(message)

    private fun runOnce(command: Command, dir: Path, attempt: Int): Int {
        val i = command.index
        val measurementId = if (profileCli) UUID.randomUUID().toString() else null
        val profile = measurementId?.let { "$HOME/job/cases/$i/profile-$it" }
        val options = profile?.let(CliMeasurements::options).orEmpty()
        val javaOptions = "$solveJavaOpts -XX:ActiveProcessorCount=${command.cores} $options".trim()
        val script = """
            $ENV
            export JOB_DIR=$HOME/job KLAUSE_CLI_OPTS=${quote(javaOptions)}
            export OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1 OMP_NUM_THREADS=1
            mkdir -p ${'$'}JOB_DIR/cases
            ${profile?.let { "mkdir -p ${quote(it)}; export KLAUSE_LAB_PROFILE_DIR=${quote(it)}" }.orEmpty()}
            timeout -k 30 ${command.timeoutSec} bash -c ${quote(command.cmd)} > ${'$'}JOB_DIR/$i.out 2> ${'$'}JOB_DIR/$i.err
            echo "exit=${'$'}?"
            echo "$RECORD"
            cat ${'$'}JOB_DIR/cases/$i/*.json 2>/dev/null || true
        """.trimIndent()
        val process = ssh.start(script, mergeErrors = false)
        val output = StringBuilder()
        val reader = thread(isDaemon = true) { output.append(process.inputStream.bufferedReader().readText()) }
        val deadline = System.currentTimeMillis() + (command.timeoutSec + SSH_GRACE_SEC) * MS_PER_SEC
        while (!process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
            if (cancelled() || System.currentTimeMillis() > deadline) {
                process.destroyForcibly()
                ssh.exec("pkill -f -- 'cases/$i([^0-9]|${'$'})' || true", KILL_TIMEOUT_SEC)
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
        // Its output, for a case whose record cannot explain what happened: two more SSH round trips each, a cost every
        // case of a job would pay for nothing when it ran cleanly.
        if (scriptJob) fetchScriptOutput(i, dir)
        if (keepsOutput(result, record.isNotEmpty(), profileCli || scriptJob)) for (suffix in listOf("out", "err")) {
            runCatching { ssh.download("$HOME/job/$i.$suffix", dir.resolve("$i.$suffix"), ARTIFACT_TRANSFER_SEC) }
                .onFailure { error ->
                    dir.resolve("$i.$suffix.transfer-error").toFile()
                        .writeText("${error.message ?: error::class.simpleName}\n")
                }
        }
        if (profile != null) {
            val files = dir.resolve("cases/$i/profile-$measurementId").createDirectories()
            val recordHash = record.takeIf { it.isNotEmpty() }?.let { snapshot ->
                files.resolve("solve-record.json").toFile().writeText(snapshot)
                MessageDigest.getInstance("SHA-256").digest(snapshot.toByteArray()).joinToString("") { "%02x".format(it) }
            }
            files.resolve("measurement.json").toFile().writeText(Json { encodeDefaults = true }.encodeToString(
                CliMeasurementManifest.serializer(), CliMeasurementManifest(
                    i, attempt, requireNotNull(measurementId), instance, command.cmd, exit, javaOptions, recordHash)))
            for (name in CliMeasurements.artifacts) ssh.download("$profile/$name", files.resolve(name), ARTIFACT_TRANSFER_SEC)
        }
        return result
    }

    /** A script case's `$OUT` directory, packed on the instance and unpacked under its case in the job directory. */
    private fun fetchScriptOutput(i: Int, dir: Path) {
        val case = dir.resolve("cases/$i").createDirectories()
        val archive = case.resolve("out.tar")
        runCatching {
            receiveFile(ssh.start("tar -C ${quote("$HOME/job/cases/$i")} -c out", mergeErrors = false), archive, ARTIFACT_TRANSFER_SEC, cancelled)
            val unpack = ProcessBuilder("tar", "-x", "-f", archive.toString(), "-C", case.toString()).redirectErrorStream(true).start()
            val output = unpack.inputStream.bufferedReader().readText()
            check(unpack.waitFor() == 0) { "unpacking failed: $output" }
            Files.deleteIfExists(archive)
        }.onFailure { error ->
            case.resolve("out.transfer-error").toFile().writeText("${error.message ?: error::class.simpleName}\n")
        }
    }

    companion object {
        /** Whether a case's stdout and stderr come back from its instance: when it failed, timed out or left no
         *  record, and for every case of a profiling job. */
        internal fun keepsOutput(result: Int, recorded: Boolean, profileCli: Boolean): Boolean =
            profileCli || result != 0 || !recorded

        const val HOME = "/home/ubuntu"
        const val ENV = "export JAVA_HOME=/opt/jdk PATH=/opt/jdk/bin:/usr/local/bin:${'$'}PATH"
        private const val SHA_DIR = 12
        private const val RECORD = "----klause-lab-record----"
        private const val SELECT_TIMEOUT_SEC = 3600L
        private const val SSH_GRACE_SEC = 120L
        private const val KILL_TIMEOUT_SEC = 30L
        private const val POLL_MS = 1000L
        private const val READ_WAIT_MS = 5_000L
        private const val ARTIFACT_TRANSFER_SEC = 120L
        private const val MS_PER_SEC = 1000L
        private const val ERROR_CHARS = 2000
        private const val TIMEOUT_EXIT_CODE = 124
        private val SSH_RETRY = Backoff(attempts = 8, baseMs = 15_000, maxMs = 300_000)

        /** The runner's exits for a case cut off by a cancel or by its timeout. */
        const val CANCELLED = -1000
        const val TIMEOUT = -1001
    }
}

/**
 * Runs `"host": "aws"` experiments on EC2, on demand: no instance runs while none is queued. A job takes up to its
 * `machines` instances (all the free ones by default, at most [AwsConfig.maxInstances] over every job), each built
 * at the job's commits, plans on the first, and splits its problems across them, every arm of a problem on the same
 * instance, so each comparison is made on one machine. Records come back as cases end, so the job's page, its
 * comparisons and its CSVs read as a local job's. The instances are terminated when the job ends, whatever way it
 * ends; one the lab lost track of powers itself off after [AwsConfig.maxHours], and the worker terminates any lab
 * instance no running job owns when it starts.
 */
class AwsWorker(
    private val runner: Runner,
    private val store: Store,
    private val config: Config,
    initial: AwsConfig,
) {
    /** The settings, read again from `aws.properties` on every poll: a raised quota or a new default applies to the
     *  next job without restarting the lab. */
    @Volatile
    private var aws: AwsConfig = initial
    private val cli: AwsCli get() = AwsCli(aws)
    private val settings = config.dataDir.resolve("aws").resolve("aws.properties")
    private val busy = AtomicInteger()
    /** Numbers the instances' names. */
    private val launched = AtomicInteger()
    /** How each running instance was bought, `spot` or `on-demand`, for the job page. */
    private val markets = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Whether any AWS job is running: the runner holds off updating itself until none is, since a restart would end
     *  its thread and, with it, the job's instances. */
    val active: Boolean get() = busy.get() > 0
    private val knownHosts = config.dataDir.resolve("aws").resolve("known_hosts")

    fun loop() {
        knownHosts.parent.createDirectories()
        runCatching { recover() }.onFailure { println("aws: recovery failed: ${it.message}") }
        var swept = System.currentTimeMillis()
        while (true) {
            runCatching { AwsConfig.load(settings)?.let { aws = it } }.onFailure { println("aws: settings not reread: ${it.message}") }
            runCatching { claim() }.onFailure { println("aws: ${it.message}") }
            if (System.currentTimeMillis() - swept > SWEEP_MS) {
                swept = System.currentTimeMillis()
                runCatching { sweep() }.onFailure { println("aws: sweep failed: ${it.message}") }
            }
            Thread.sleep(POLL_MS)
        }
    }

    /**
     * Take the next AWS job if the vCPUs it needs are free: instances of the size its arms' `processors` call for
     * ([AwsConfig.sizeFor]), as many as it asks for or the quota leaves, at least one.
     */
    private fun claim() {
        // A waiting update needs every AWS job to have ended; starting another would hold it back indefinitely.
        if (runner.updateWaiting()) return
        val next = store.queueOrder(Experiments.AWS_HOST).firstOrNull()?.let(store::job) ?: return
        val spec = checkNotNull(next.experiment)
        val cores = Experiments.arms(spec).maxOf { it.cores }
        val size = aws.sizeFor(cores)
        if (size == null) {
            val job = store.next(Experiments.AWS_HOST) ?: return
            store.finish(job.id, Status.FAILED, "its cases need $cores cores, more than an instance within the ${aws.vcpuQuota}-vCPU quota has")
            return
        }
        // Reserved before the job is claimed, since a running job may take the same vCPUs to grow.
        val held = Reservation(size)
        val machines = ((spec.machines ?: aws.maxInstances).coerceAtLeast(1) downTo 1).firstOrNull { held.reserve(it) } ?: return
        val job = store.next(Experiments.AWS_HOST)
        if (job?.id != next.id) {
            held.releaseAll()
            check(job == null) { "claimed ${job?.id}, expected ${next.id}" }
            return
        }
        thread(name = "aws-${job.id}") {
            try {
                work(job, machines, size, held)
            } finally {
                held.releaseAll()
            }
        }
    }

    /** The vCPUs one job holds of the quota, by instance of [size]: reserved before a launch, released as soon as an
     *  instance is not needed or AWS did not give it. */
    private inner class Reservation(private val size: InstanceSize) {
        private val instances = AtomicInteger()

        /** Reserve [n] more instances, if the quota has room for them all. */
        fun reserve(n: Int): Boolean {
            while (true) {
                val taken = busy.get()
                if (taken + n * size.vcpus > aws.vcpuQuota) return false
                if (busy.compareAndSet(taken, taken + n * size.vcpus)) break
            }
            instances.addAndGet(n)
            return true
        }

        fun release(n: Int) {
            instances.addAndGet(-n)
            busy.addAndGet(-n * size.vcpus)
        }

        fun releaseAll() = release(instances.get())
    }

    /** After a restart: jobs left running go back in the queue, and instances no running job owns are terminated. */
    private fun recover() {
        for (job in store.active().filter { it.status == Status.RUNNING && it.experiment?.host == Experiments.AWS_HOST }) {
            store.requeueInterrupted(job.id)
            store.requeue(job.id)
        }
        sweep()
    }

    /** Terminate the lab's instances no running job owns: ones a job could not terminate when it ended, as when the
     *  lab machine's network was down then. They would hold the vCPU quota against every launch. */
    private fun sweep() {
        val owned = store.active().filter { it.status == Status.RUNNING }.map { it.id }.toSet()
        val orphans = cli.running().filter { (_, job) -> job == null || job !in owned }.map { it.first }
        if (orphans.isNotEmpty()) {
            println("aws: terminating instances no running job owns: $orphans")
            cli.terminate(orphans)
        }
    }

    private fun work(claimed: Job, machines: Int, size: InstanceSize, held: Reservation) {
        val dir = config.jobDir(claimed.id).createDirectories()
        val log = dir.resolve("setup.log").toFile().apply { appendText("") }
        val instances: MutableList<String> = Collections.synchronizedList(ArrayList())
        try {
            val job = checkNotNull(store.job(claimed.id))
            val spec = checkNotNull(job.experiment)
            val (arms, shas) = runner.resolveArms(job, spec, log)
            val primary = shas.getValue(arms.first().ref)
            val hosts = launch(job, machines, size, instances)
            // A job that got fewer instances than it reserved hands the rest back, so the next job can use them.
            held.release(machines - hosts.size)
            parallelOn(hosts) { host -> setup(job, host, shas.values.distinct(), log) }
            if (job.commands.isEmpty()) {
                runner.log(job.id, "${hosts.first().instance}: planning, which fetches the corpora the selections read")
                runner.plan(job, spec, arms, shas, primary, dir, hosts.first())
            }
            aws.corpusBucket?.let { bucket ->
                // Right after planning, before the other instances fetch: they then pull what the first fetched from
                // the bucket instead of each fetching it from its source, and no case is running to slow the upload.
                runner.log(job.id, "${hosts.first().instance}: compressing and pushing newly fetched corpora to s3://$bucket")
                // A collection fetched from its source is plain text, many times its compressed size: compressed as the
                // bench stores it, it takes less of the bucket, of every instance's disk and of every sync.
                val bench = "${hosts.first().worktree(primary)}/klause-bench"
                val compress = "${AwsHost.ENV}; cd ${quote(bench)} && JAVA_OPTS='-Dklause.workspace.root=${hosts.first().worktree(primary)}' " +
                    "./build/install/klause-bench/bin/klause-bench corpus compress ~/corpus > ~/corpus-compress.log 2>&1"
                runCatching { hosts.first().ssh.run("$compress; ${s3Sync("~/corpus", "s3://$bucket/corpus")}", S3_PUSH_TIMEOUT_SEC) }
                    .onFailure { runner.log(job.id, "corpus push failed, the other instances fetch upstream: ${it.message}") }
            }
            if (hosts.size > 1) runner.log(job.id, "fetching the corpora on the other instances")
            val selections = spec.problems.map { Experiments.selectArgs(it) }
            parallelOn(hosts.drop(1)) { host -> fetchCorpora(host, primary, selections) }
            store.setup(job.id, primary)
            // Unset, a job runs one case per two physical cores of an instance: each case is a bench JVM driving a
            // solver JVM, and their JIT and GC threads take the rest. The core budget then fits cases that need more.
            if (spec.parallel == null) store.setParallel(job.id, defaultParallel(size))
            val problems = ProblemQueue(store.cases(job.id))
            runner.log(job.id, "running ${problems.size} problems on ${hosts.size} instances, each taking the next problem as it has room")
            val fleet = Fleet(job, dir, size, held, instances, problems) { host ->
                setup(job, host, shas.values.distinct(), log)
                fetchCorpora(host, primary, selections)
            }
            val outcomes = fleet.run(hosts)
            when {
                Dispatched.CANCELLED in outcomes -> runner.cancel(job.id)
                Dispatched.YIELDED in outcomes -> {
                    store.requeue(job.id)
                    runner.log(job.id, "job paused between commands; its instances are terminated and launched again on resume")
                }
                // Instances taken back with work left and none launched in their place: the job waits for capacity.
                Dispatched.LOST in outcomes && checkNotNull(store.job(job.id)).commands.any { it.status == Status.QUEUED } -> {
                    store.requeue(job.id)
                    runner.log(job.id, "instances were taken back by AWS with cases left: queued again, to finish on new instances")
                }
                else -> {
                    store.finish(job.id, Status.DONE)
                    runner.log(job.id, "job finished")
                }
            }
        } catch (e: Exception) {
            if (store.cancelRequested(claimed.id)) {
                runner.cancel(claimed.id)
            } else {
                runner.log(claimed.id, "job failed: ${e.message}")
                store.cancelRemaining(claimed.id)
                store.finish(claimed.id, Status.FAILED, e.message ?: e.toString())
            }
        } finally {
            val left = synchronized(instances) { instances.toList() }
            if (left.isNotEmpty()) {
                // Retried, since the lab machine's network may be what ended the job; what still fails the sweep in
                // [loop] terminates within minutes.
                runCatching {
                    retrying(config.retry, onRetry = { _, wait, e ->
                        runner.log(claimed.id, "terminating $left failed, retrying in ${wait / MS_PER_SEC} s: ${e.message}")
                    }) { cli.terminate(left) }
                }.onSuccess { runner.log(claimed.id, "terminated $left") }
                    .onFailure { runner.log(claimed.id, "could not terminate $left; the next sweep will: ${it.message}") }
            }
            Files.deleteIfExists(config.jobDir(claimed.id).resolve(INSTANCES_FILE))
        }
    }

    /** Each instance has the corpora its cases read before they start, so cases that share a collection never fetch
     *  it side by side: from the bucket first, then whatever a selection still lacks. */
    private fun fetchCorpora(host: AwsHost, primary: String, selections: List<String>) {
        aws.corpusBucket?.let { bucket -> runCatching { host.ssh.run(s3Sync("s3://$bucket/corpus", "~/corpus"), S3_PUSH_TIMEOUT_SEC) } }
        selections.forEach { host.select(host.worktree(primary), it) }
    }

    /**
     * A job's instances while its cases run. Each takes the next problem left whenever it has room for a case, so a
     * problem's arms, seeds and repeats all run on one instance and every instance stays busy to the end. While
     * problems are left, the job asks for no more than its `machines` (default [AwsConfig.maxInstances]) and no AWS job
     * waits in the queue, it launches another instance whenever the quota has room for one: a job that started with
     * the few instances the quota had left grows as other jobs end. An instance with nothing left to take is
     * terminated at once, handing its vCPUs to the jobs still running or waiting.
     */
    private inner class Fleet(
        private val job: Job,
        private val dir: Path,
        private val size: InstanceSize,
        private val held: Reservation,
        private val instances: MutableList<String>,
        private val problems: ProblemQueue,
        private val prepare: (AwsHost) -> Unit,
    ) {
        private val pool = Executors.newCachedThreadPool()
        private val dispatches = Collections.synchronizedList(ArrayList<Future<Dispatched>>())

        fun run(hosts: List<AwsHost>): List<Dispatched> {
            try {
                hosts.forEach(::start)
                var growing: Future<*>? = null
                var nextGrowth = 0L
                while (dispatches.toList().any { !it.isDone } || growing?.isDone == false) {
                    if (growing?.isDone != false && System.currentTimeMillis() >= nextGrowth && wantsMore() && held.reserve(1)) {
                        growing = pool.submit { if (!grow()) nextGrowth = System.currentTimeMillis() + GROW_RETRY_MS }
                    }
                    Thread.sleep(GROW_POLL_MS)
                }
                growing?.get()
                return dispatches.toList().map { it.get() }
            } finally {
                pool.shutdownNow()
            }
        }

        private fun start(host: AwsHost) {
            dispatches += pool.submit<Dispatched> {
                val outcome = runner.dispatch(checkNotNull(store.job(job.id)), dir, host, problems::take) { held ->
                    problems.giveBack(held)
                }
                when (outcome) {
                    Dispatched.FINISHED -> retire(host.instance, "has no problems left to take")
                    // Growing then replaces it, or the job's other instances take its problems.
                    Dispatched.LOST -> retire(host.instance, "was taken back by AWS")
                    else -> Unit
                }
                outcome
            }
        }

        private fun wantsMore(): Boolean {
            if (problems.isEmpty() || store.cancelRequested(job.id)) return false
            val current = store.job(job.id) ?: return false
            val wanted = current.experiment?.machines ?: aws.maxInstances
            return !current.paused && instances.size < wanted && store.queueOrder(Experiments.AWS_HOST).isEmpty() &&
                !runner.updateWaiting()
        }

        /** Launch, set up and start one more instance on the reservation just made; false when it did not join. */
        private fun grow(): Boolean {
            val added = runCatching { launch(job, 1, size, instances, waitForCapacity = false) }
                .onFailure { runner.log(job.id, "no instance added: ${it.message}") }.getOrNull().orEmpty()
            val host = added.singleOrNull() ?: return false.also { held.release(1) }
            val joined = runCatching { prepare(host) }
                .onFailure { runner.log(job.id, "${host.instance} did not join: ${it.message}") }.isSuccess &&
                wantsToStart()
            if (!joined) {
                retire(host.instance, "was not needed after all")
                return false
            }
            runner.log(job.id, "${host.instance} joined; ${instances.size} instances now")
            start(host)
            return true
        }

        private fun wantsToStart(): Boolean =
            !problems.isEmpty() && !store.cancelRequested(job.id) && store.job(job.id)?.paused == false

        private fun retire(instance: String, why: String) {
            if (!instances.remove(instance)) return
            writeInstances(job, instances, size)
            runCatching { retrying(config.retry) { cli.terminate(listOf(instance)) } }
                .onSuccess { runner.log(job.id, "$instance $why: terminated") }
                .onFailure { runner.log(job.id, "$instance $why; could not terminate it, the next sweep will: ${it.message}") }
            held.release(1)
        }
    }

    private fun writeInstances(job: Job, instances: List<String>, size: InstanceSize) {
        val ids = synchronized(instances) { instances.toList() }
        config.jobDir(job.id).resolve(INSTANCES_FILE).toFile()
            .writeText(ids.joinToString("\n", postfix = "\n") { "$it ${size.type} ${markets[it] ?: "on-demand"}" })
    }

    /**
     * One instance: spot when [AwsConfig.spot], and on demand when AWS has no spot capacity or quota for it, or the
     * account cannot launch spot at all, and [AwsConfig.spotFallback] allows; other failures propagate as they would on demand.
     * A script job is always on demand: its one case cannot resume, so a reclaim would rerun it from the start.
     */
    private fun launchOne(job: Job, name: String, userData: File, type: String): String {
        if (!aws.spot || job.experiment?.isScript == true) return cli.launch(job.id, name, userData, type).also { markets[it] = "on-demand" }
        return runCatching { cli.launch(job.id, name, userData, type, spot = true).also { markets[it] = "spot" } }.getOrElse { e ->
            val reason = (CAPACITY.find(e.message.orEmpty()) ?: NO_SPOT.find(e.message.orEmpty()))?.value
            if (!aws.spotFallback || reason == null) throw e
            runner.log(job.id, "no spot for $type ($reason): launching on demand")
            cli.launch(job.id, name, userData, type).also { markets[it] = "on-demand" }
        }
    }

    /** Launch [machines] instances for [job], recording each in [instances] at once so that it is terminated whatever
     *  happens next, and return the new ones. [waitForCapacity] retries a while when AWS refuses for its quota. */
    private fun launch(
        job: Job, machines: Int, size: InstanceSize, instances: MutableList<String>, waitForCapacity: Boolean = true,
    ): List<AwsHost> {
        runCatching { cli.admitThisMachine() }
        val new = ArrayList<String>()
        val userData = Files.createTempFile("klause-lab-user-data", ".sh").toFile()
        try {
            userData.writeText(bootstrap())
            var refused = false
            repeat(machines) {
                if (refused) return@repeat
                val name = "klause-lab-${job.id}-${launched.incrementAndGet()}"
                // AWS refusing more for a quota or a lack of capacity is not a reason to fail a job that already has
                // instances: it runs on those. Without any, the launch is retried like any other step.
                val id = runCatching {
                    if (waitForCapacity) launchWhenCapacity(job, name, userData, size.type) else launchOne(job, name, userData, size.type)
                }.getOrElse { e ->
                    if (instances.isNotEmpty() && CAPACITY.containsMatchIn(e.message.orEmpty())) {
                        runner.log(job.id, "AWS gave no more instances (${CAPACITY.find(e.message.orEmpty())?.value}); running on ${instances.size}")
                        refused = true
                        return@repeat
                    }
                    runner.withRetry(job.id, "launching an instance") { launchOne(job, name, userData, size.type) }
                }
                new += id
                instances += id
            }
        } finally {
            userData.delete()
        }
        if (new.isEmpty()) return emptyList()
        runner.log(job.id, "launched ${size.type} instances $new")
        writeInstances(job, instances, size)
        return new.map { id ->
            runner.withRetry(job.id, "waiting for $id") { cli.awaitRunning(id) }
            val ip = runner.withRetry(job.id, "reading $id's address") { cli.publicIp(id).also { check(it != "None") { "no public address yet" } } }
            AwsHost(id, Ssh(id, ip, aws, knownHosts) { store.cancelRequested(job.id) }, size.cores, config.solveJavaOpts,
                job.experiment?.profileCli == true, job.experiment?.isScript == true,
                gone = { cli.state(id).takeIf { (state, _) -> state in GONE }?.let { (state, reason) -> "$state, $reason".trimEnd(',', ' ') } },
            ) {
                store.cancelRequested(job.id)
            }
        }
    }

    /**
     * Launch one instance, retrying for a couple of minutes while AWS refuses for a quota or a lack of capacity: instances
     * a job just terminated hold their vCPUs against the quota for a minute or so after it ended.
     */
    private fun launchWhenCapacity(job: Job, name: String, userData: File, type: String): String =
        retrying(
            CAPACITY_WAIT,
            onRetry = { _, wait, e -> if (!CAPACITY.containsMatchIn(e.message.orEmpty())) throw e
                runner.log(job.id, "AWS refused another instance (${CAPACITY.find(e.message.orEmpty())?.value}); trying again in ${wait / MS_PER_SEC} s") },
            sleep = { wait -> check(!store.cancelRequested(job.id)) { "cancelled" }; Thread.sleep(wait) },
        ) { launchOne(job, name, userData, type) }

    /** Wait for [host]'s bootstrap, then clone the repository and build each of [shas] on it. */
    private fun setup(job: Job, host: AwsHost, shas: List<String>, log: File) {
        runner.withRetry(job.id, "waiting for ${host.instance} to boot", BOOT_WAIT) {
            host.ssh.run("test -f /var/lib/klause-ready", SSH_STEP_SEC)
        }
        runner.log(job.id, "${host.instance}: booted; building ${shas.joinToString { it.take(SHA_LOG) }}")
        val script = buildString {
            appendLine("set -euo pipefail; ${AwsHost.ENV}")
            appendLine(AwsBuildCache.functions)
            appendLine("mkdir -p ~/work ~/job/cases ~/corpus")
            // The corpus comes down from the bucket while the build runs; a failed sync only means fetching upstream.
            aws.corpusBucket?.let { appendLine("(${s3Sync("s3://$it/corpus", "~/corpus")} || true) > ~/corpus-sync.log 2>&1 & sync=${'$'}!") }
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
                finishWorktree(worktree, job.experiment?.profileCli == true).forEach(::appendLine)
                appendLine("validate_lab_provenance ${quote(worktree)}")
            }
            if (bucket != null) {
                appendLine("if [ ${'$'}built = 1 ]; then publish_lab_cache s3://$bucket/$GRADLE_ARCHIVE ~ ${aws.region} .gradle/caches/modules-2 .gradle/wrapper; fi")
                appendLine("wait ${'$'}sync || true")
            }
        }
        runner.withRetry(job.id, "building on ${host.instance}", config.buildRetry) {
            val (exit, out) = host.ssh.exec(script, BUILD_TIMEOUT_SEC)
            log.appendText("[${host.instance}] $out\n")
            check(exit == 0) { "build on ${host.instance} failed (exit $exit); see setup.log" }
        }
        runner.log(job.id, "${host.instance}: built")
    }

    /** `aws s3 sync` with the instance role, leaving out the bench's staging copies of compressed instances. */
    private fun s3Sync(from: String, to: String) =
        "aws s3 sync $from $to --region ${aws.region} --only-show-errors --exclude '.plain/*' --exclude '*/.plain/*'"

    private fun <T, R> parallelOn(items: List<T>, block: (T) -> R): List<R> {
        if (items.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(items.size)
        try {
            return items.map { item -> pool.submit<R> { block(item) } }.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    /** What a fresh instance runs at first boot: its power-off deadline, then a JDK, MiniZinc and the tools a build and
     *  the bench need, then the mark [setup] waits for. */
    private fun bootstrap(): String = """
        #!/bin/bash
        set -euxo pipefail
        shutdown -h +${aws.maxHours * MINUTES_PER_HOUR}
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -q
        apt-get install -yq git zstd unzip curl xz-utils time python3
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

        /** Cases an instance of [size] runs at once when a job sets no `parallel`: one per two physical cores. */
        internal fun defaultParallel(size: InstanceSize): Int = (size.cores / 2).coerceAtLeast(1)

        /** The job's running instances, one id a line, while it has them: what the pages show. */
        const val INSTANCES_FILE = "aws-instances"

        private const val POLL_MS = 10_000L
        /** The states of an instance that will not run a case again. */
        private val GONE = setOf("shutting-down", "terminated", "stopping", "stopped")
        /** How often a running job looks for room to add an instance, and how long it waits after an attempt failed. */
        private const val GROW_POLL_MS = 10_000L
        private const val GROW_RETRY_MS = 120_000L
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
        /** A fresh instance takes a few minutes to boot and install: tries every 20 s, up to about 15 minutes. */
        /** Retries of a launch AWS refused for capacity: every 30 s for about two minutes. */
        private val CAPACITY_WAIT = Backoff(attempts = 5, baseMs = 30_000, maxMs = 30_000)
        private const val MS_PER_SEC = 1000L

        private val BOOT_WAIT = Backoff(attempts = 45, baseMs = 20_000, maxMs = 20_000)
    }
}

/** A job's problems not yet taken by an instance, in plan order, each as the indices of its cases: every arm, seed and
 *  repeat of a problem goes to the instance that takes it, so each comparison is made on one machine. */
internal class ProblemQueue(cases: List<CaseResult>) {
    private val left = ArrayDeque(cases.filter { it.status == Status.QUEUED }
        .groupBy { it.problem.suite to it.problem.problem }.values.map { group -> group.map { it.index } })

    val size: Int @Synchronized get() = left.size

    @Synchronized
    fun take(): List<Int>? = left.removeFirstOrNull()

    @Synchronized
    fun isEmpty(): Boolean = left.isEmpty()

    /** Cases a lost host held, back at the front: the next host with room takes them first. */
    @Synchronized
    fun giveBack(indices: Collection<Int>) {
        if (indices.isNotEmpty()) left.addFirst(indices.sorted())
    }
}
