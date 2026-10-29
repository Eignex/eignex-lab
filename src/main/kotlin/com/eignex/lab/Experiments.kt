package com.eignex.lab

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonTransformingSerializer

/** A repository-owned benchmark or explicit committed profile, expanded into paired configuration arms. */
@Serializable
data class ExperimentSpec(
    val name: String,
    /** The problem selections; none for a profile job. */
    @Serializable(with = SelectionsSerializer::class)
    val problems: List<Map<String, String>> = emptyList(),
    val base: Map<String, String> = emptyMap(),
    val configs: List<Map<String, String>> = listOf(emptyMap()),
    val grid: Map<String, List<String>> = emptyMap(),
    /** Solver seeds; empty runs each case once on the bench's fixed seed. */
    val seeds: List<Long> = emptyList(),
    /** Runs of each (problem, configuration, seed), identical but for the machine's noise, which they measure. */
    val repeats: Int = 1,
    /** Cases run at once; unset, as many as the lab allows, within its core budget either way. */
    val parallel: Int? = null,
    val priority: Int = 0,
    /** Run even when the estimate is over [Config.maxExperimentHours]. */
    val confirm: Boolean = false,
    /**
     * Recorded placement, resolved by [Placement]; this is not a submission field. Auto placement
     * is preferred and is the default for ordinary submissions: omit tags. `auto` lets local and
     * cloud workers share the job, respecting priority; `aws` is cloud-only and `lab` is local-only. The serialized
     * default below preserves historical specs and local schedules; submission placement is decided by [Placement].
     */
    val host: String = Experiments.LAB_HOST,
    /** Exact problems, as a plan recorded them, run instead of selecting: what a confirmation job ([Confirm]) reruns. */
    val problemList: List<Problem> = emptyList(),
    /** What the experiment tests and why, in a sentence or two: the queue, the history and the job page show it. */
    val description: String = "",
    /** One recording mode for experiment CLI cases; omitted for ordinary runs. */
    val measurement: Measurement = Measurement.NONE,
    /**
     * An analysis job: the lab's own analyser run again over an ended job's retained captures, writing fresh reports.
     * Nothing is built and no consumer code runs. Its spec names nothing else.
     */
    val analyze: AnalyzeSpec? = null,
    /**
     * A profile job: a committed JVM main or executable at the base `ref`, instead of problems and arms.
     * For measurements the experiments cannot express; not for the build's tests or lint gates, which CI runs.
     */
    val profile: ProfileMain? = null,
    /** Retired: a shell script a job once ran. Kept so the jobs that did still read; a spec naming one is refused. */
    val script: String? = null,
    /** Retired with [script]. */
    val scriptFile: String? = null,
    /** What the lab cut down to fit the local machine when it took the job ([Experiments.capToLocal]), each in words;
     *  set by the lab, not the submitter. */
    val capped: List<String> = emptyList(),
    /** The GitHub repository to build. Only repositories owned by Eignex are accepted. */
    val repository: String = Repositories.DEFAULT,
    /** Internal selector in stored recipes. Public submissions use a flat tags array, decoded by [experimentInput]. */
    val nodeTags: NodeSelector? = null,
    /** Repository-owned benchmark plan and execution. */
    val bench: BenchSpec? = null,
)

@Serializable
enum class Measurement {
    @SerialName("none") NONE,
    @SerialName("resources") RESOURCES,
    @SerialName("jfr") JFR,
    @SerialName("compilation") COMPILATION,
    @SerialName("compilation+log") COMPILATION_LOG;

    val recordsJfr: Boolean get() = this == JFR || this == COMPILATION || this == COMPILATION_LOG
    val compilationRecording: String? get() = when (this) {
        COMPILATION -> "jfr"
        COMPILATION_LOG -> "jfr+log"
        else -> null
    }
}

/**
 * A profile job's entry point: [main] on its build's classpath, or a committed [script], with [args] passed literally.
 * Only repositories owned by Eignex run, from a commit that passes [Trust]. JVM mains record JFR; scripts own their
 * runtimes and recordings and may publish a report through [outputArgument].
 */
@Serializable
data class ProfileMain(
    val main: String = "",
    val args: List<String> = emptyList(),
    /**
     * Optional local active-time limit including build, retries and cleanup; queue time is excluded. It is capped as
     * the profile's own time limit is ([Experiments.profileRunTimeoutSec]).
     */
    val runTimeoutSec: Long? = null,
    /** An executable committed in the selected repository, instead of a JVM main. */
    val script: String? = null,
    /** Explicit Gradle tasks that prepare this entry point. */
    val buildTasks: List<String> = emptyList(),
    /** Repository-relative runtime entries, optionally naming a directory of jars. Required for every JVM main. */
    val classpath: List<String> = emptyList(),
    /** JVM options for a main; a script controls its own runtimes and recordings. */
    val jvmArgs: List<String> = emptyList(),
    /** Pass this option followed by $OUT/report to a script that publishes a report directory. */
    val outputArgument: String? = null,
)

/**
 * What an analysis job reruns: the analyser of [kind] (`compilation`, the only one) over [job]'s retained captures, or
 * over the captures in a saved-capture [archive] ([CaptureArchives]), a path under the lab's archive directory. An
 * archive is pinned by its [sha256]; its [manifest] is the `.manifest.json` beside it unless named, and
 * [manifestSha256] pins that too when given.
 */
@Serializable
data class AnalyzeSpec(
    val job: Long? = null,
    val kind: String = COMPILATION,
    val archive: String? = null,
    val sha256: String? = null,
    val manifest: String? = null,
    val manifestSha256: String? = null,
) {
    companion object {
        const val COMPILATION = "compilation"
        /** The lab's source checkers ([com.eignex.lab.checker]) run again over an ended job's records: a recheck. */
        const val CHECK = "check"
        val KINDS = setOf(COMPILATION, CHECK)
    }
}

/** Whether [this] is an analysis job, which runs the lab's analyser over another job's captures. */
val ExperimentSpec.isAnalysis: Boolean get() = analyze != null

/** Whether each CLI process runs under the lab's physical resource measurement wrapper. */
val ExperimentSpec.measuresCli: Boolean get() = measurement != Measurement.NONE

val ExperimentSpec.recordsCliJfr: Boolean get() = measurement.recordsJfr

/** The recording detail passed to the JFR and HotSpot launchers. */
val ExperimentSpec.compilationRecording: String? get() = measurement.compilationRecording

/** Whether [this] job's cases are instrumented: their CLI recorded every compilation, so their times are no runtime measurement. */
val ExperimentSpec.instrumented: Boolean get() = compilationRecording != null

/** Whether [this] is a profile job rather than an experiment over problems. */
val ExperimentSpec.isProfile: Boolean get() = profile != null

/** Whether [this] is one of the retired script jobs, which the lab no longer runs. */
val ExperimentSpec.isScript: Boolean get() = !script.isNullOrBlank() || !scriptFile.isNullOrBlank()

/** One configuration of an experiment: its [label] and the solve arguments, `ref` included. */
@Serializable
data class Arm(val label: String, val values: Map<String, String>) {
    val ref: String get() = values["ref"] ?: DEFAULT_REF
    val timeoutMs: Long get() = values["timeout"]?.toLong() ?: DEFAULT_TIMEOUT_MS

    /** Cores each of the arm's solves keeps busy: its portfolio's `processors`, one without. */
    val cores: Int get() = values["processors"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1

    /** Historical recording flag retained when reading old case metadata. */
    val debug: Boolean get() = values[Experiments.DEBUG_KEY]?.toBooleanStrictOrNull() ?: true
}

/** A portable workload and optional source files for independent reference solving and checking. */
@Serializable
data class Problem(
    val suite: String,
    val problem: String,
    val collection: String = "",
    val family: String = "",
    val format: String = "",
    val category: String = "",
    val model: String? = null,
    val data: String? = null,
    val includeDirs: List<String> = emptyList(),
    val benchCase: com.eignex.lab.bench.BenchCase? = null,
) {
    /** Source identity shared across runtime configurations, seeds and repeats. */
    val referenceKey: Pair<String, String> get() = collection to (benchCase?.attributes?.get("problem") ?: problem)
}

/** The case at one index of an experiment's commands. */
data class Case(val problem: Int, val arm: Int, val seed: Long?, val repeat: Int = 0)

object Experiments {
    /** The stored host values: the local machine, a cloud node, and whichever is free first. */
    const val LAB_HOST = "lab"
    const val AWS_HOST = "aws"
    const val AUTO_HOST = "auto"
    /** What the pages and messages call [LAB_HOST] and [AWS_HOST], also accepted wherever a host is given. */
    const val LOCAL_HOST = "local"
    const val CLOUD_HOST = "cloud"

    /** [host] as the lab stores it: `local` is `lab` and `cloud` is `aws`; any other value as it is. Old jobs and
     *  old clients keep the stored names, so nothing needs migrating. */
    fun storedHost(host: String): String = when (host) {
        LOCAL_HOST -> LAB_HOST
        CLOUD_HOST -> AWS_HOST
        else -> host
    }

    /** [host] in the words the pages and messages use: `local`, `cloud` or `auto`. */
    fun hostWord(host: String): String = when (storedHost(host)) {
        LAB_HOST -> LOCAL_HOST
        AWS_HOST -> CLOUD_HOST
        else -> host
    }

    /**
     * What [spec] measures, as a short hash: its problems, arms, seeds and repeats. Its name, the ref a schedule pins
     * on each run, and how it is run (parallel, priority, confirm) are left out, so every run of an unchanged schedule
     * shares one fingerprint and an edited one starts another. Where it runs ([ExperimentSpec.host]) is left out
     * too: a schedule moved between the local machine and the cloud, or a run taken
     * by either, still measures the same thing, and a stored spec that names any host matches one that names another.
     * An arm's `debug` is measured: `debug=false` turns the CLI's runtime statistics off, which changes what a solve
     * runs and records, so it starts another fingerprint. `debug=true` is the default, so saying it changes nothing.
     */
    fun fingerprint(spec: ExperimentSpec): String {
        fun measured(values: Map<String, String>) = (if (values[DEBUG_KEY] == "true") values - DEBUG_KEY else values).toSortedMap()
        val measured = spec.copy(
            name = "",
            description = "",
            problems = spec.problems.map { it.toSortedMap() },
            base = measured(spec.base - "ref"),
            configs = spec.configs.map(::measured),
            grid = spec.grid.toSortedMap(),
            parallel = null,
            priority = 0,
            confirm = false,
            host = LAB_HOST,
            profile = spec.profile?.copy(runTimeoutSec = null),
            bench = spec.bench?.copy(runTimeoutSec = null),
            capped = emptyList(),
            nodeTags = spec.nodeTags?.let { it.copy(require = it.require.toSortedSet(), exclude = it.exclude.toSortedSet()) }
                ?.takeIf { it.require.isNotEmpty() || it.exclude.isNotEmpty() },
        )
        return hash(Json.encodeToString(ExperimentSpec.serializer(), measured))
    }

    /** What a run of [spec] measured over [problems], the ones it planned: its [fingerprint] and its problem set,
     *  so a named set redrawn or a selection the reference filter shifts tells runs apart as an edited spec does. */
    fun fingerprint(spec: ExperimentSpec, problems: Collection<Problem>): String =
        hash(fingerprint(spec) + "\n" + problems.map { "${it.suite}/${it.problem}" }.sorted().joinToString("\n"))

    /**
     * A schedule's trend line: the [fingerprint] without the source-validation request ([SourceValidation.unrequested]).
     * The check runs after the solve and changes nothing it does, so checked and unchecked runs of one spec stay one
     * line, each marked as it was checked; confirmations still compare only runs of the same [fingerprint].
     */
    fun trendFingerprint(spec: ExperimentSpec): String = fingerprint(SourceValidation.unrequested(spec))

    /** [trendFingerprint] over the [problems] a run planned, as [fingerprint] with problems is. */
    fun trendFingerprint(spec: ExperimentSpec, problems: Collection<Problem>): String =
        fingerprint(SourceValidation.unrequested(spec), problems)

    /**
     * The active job [spec] would split: one queued or running, not being cancelled, that runs the same arms, seeds,
     * repeats and profiling as [spec] and differs at most in its name, description and problems. One question is one
     * experiment over all its problems; the lab bounds its runtime itself (per-case timeouts, placement on the local
     * machine or a cloud node by load), and batches defeat that placement and the arms' pairing and scatter the
     * results. Scheduled runs, confirmations and profile jobs are neither refused nor counted, and a cancelled job is
     * no longer active.
     */
    fun splitOf(spec: ExperimentSpec, active: List<Job>): Job? {
        if (exemptFromSplitting(spec)) return null
        val key = splitKey(spec)
        return active.firstOrNull { job ->
            job.status in setOf(Status.QUEUED, Status.RUNNING) && !job.cancelRequested &&
                job.experiment?.let { !exemptFromSplitting(it) && splitKey(it) == key } == true
        }
    }

    /** Why a submission that [splitOf] matched to [job] is refused, and what to do instead. */
    fun splitMessage(job: Job): String =
        "job ${job.id} (${job.name}) is already ${job.status.name.lowercase()} with these configs, seeds and repeats: " +
            "submit one experiment that covers all the problems instead of splitting it into batches. The lab bounds " +
            "runtime itself (per-case timeouts, placement on the local machine or a cloud node by load), so don't split or cancel jobs to " +
            "keep them short; read partial results with `lab cases ${job.id}` while it runs"

    private fun exemptFromSplitting(spec: ExperimentSpec) = spec.bench != null || spec.isProfile || spec.isAnalysis || '@' in spec.name || Confirm.SUFFIX in spec.name

    /** What [spec] runs apart from its problems: each arm's values with their defaults filled in, labels left out. */
    private fun splitKey(spec: ExperimentSpec): List<Any> = listOf(
        spec.nodeTags ?: NodeSelector(),
        arms(spec).map { arm -> (arm.values + ("ref" to arm.ref) + ("timeout" to arm.timeoutMs.toString())).toSortedMap().toString() }.sorted(),
        spec.seeds.sorted(),
        spec.repeats,
        spec.measurement,
    )

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(FINGERPRINT_BYTES).joinToString("") { "%02x".format(it) }

    /** The arms of [spec], in order: each config over the base, crossed with the grid's combinations. Unlabelled
     *  arms are named by the values that set them apart from the other arms. */
    fun arms(spec: ExperimentSpec): List<Arm> {
        val combinations = spec.grid.entries.fold(listOf(emptyMap<String, String>())) { acc, (key, values) ->
            acc.flatMap { combination -> values.map { combination + (key to it) } }
        }
        val merged = spec.configs.flatMap { config -> combinations.map { spec.base + config + it } }
        val varying = merged.flatMap { it.keys }.distinct()
            .filter { key -> key != "label" && merged.map { it[key] }.distinct().size > 1 }
        return merged.map { values ->
            val label = values["label"]
                ?: varying.joinToString(" ") { "$it=${values[it] ?: "-"}" }.ifEmpty { "base" }
            Arm(label, values - "label")
        }
    }

    /**
     * Every case, problem by problem. Within a problem the arms alternate: each seed and repeat runs every arm once
     * before the next starts, so an A/B with repeats goes A, B, A, B rather than A, A, B, B, and drift on the machine
     * lands on both arms alike. The arm order is rotated one place per problem, so drift over the run and a pause
     * part-way spread evenly across the arms instead of favouring the arm that always goes first.
     */
    fun cases(problems: Int, arms: Int, seeds: List<Long>, repeats: Int = 1): List<Case> = (0 until problems).flatMap { problem ->
        val order = (0 until arms).map { (it + problem) % arms }
        seeds.ifEmpty { listOf(null) }.flatMap { seed ->
            (0 until repeats).flatMap { repeat -> order.map { arm -> Case(problem, arm, seed, repeat) } }
        }
    }

    /**
     * [spec] cut down to fit the local machine, which every job the lab accepts must: each `processors` over [cores]
     * becomes [cores], and a `parallel` over [maxParallel] becomes [maxParallel]. The cloud is only overflow for the
     * local machine's queue, so no job needs a node. A grid axis whose values the cap makes equal keeps one of them.
     * What was cut is added to [ExperimentSpec.capped], which the submit response, the job log and the job page show.
     */
    fun capToLocal(spec: ExperimentSpec, cores: Int, maxParallel: Int): ExperimentSpec {
        val notes = mutableListOf<String>()
        fun capCores(value: String): String {
            val asked = value.toIntOrNull()?.takeIf { it > cores } ?: return value
            notes += "processors=$asked was asked, capped at the local machine's $cores cores"
            return cores.toString()
        }
        fun cap(values: Map<String, String>): Map<String, String> =
            values["processors"]?.let { values + ("processors" to capCores(it)) } ?: values
        val grid = spec.grid.mapValues { (key, values) -> if (key == "processors") values.map(::capCores).distinct() else values }
        val parallel = spec.parallel?.takeIf { it > maxParallel }?.let {
            notes += "parallel=$it was asked, capped at the local machine's $maxParallel"
            maxParallel
        } ?: spec.parallel
        val capped = spec.copy(base = cap(spec.base), configs = spec.configs.map(::cap), grid = grid, parallel = parallel)
        return if (notes.isEmpty()) spec else capped.copy(capped = spec.capped + notes.distinct())
    }

    /** Check the current execution contract for persisted inputs as well as new submissions. */
    internal fun requireExecutable(spec: ExperimentSpec) {
        require(spec.isAnalysis || spec.isProfile || spec.bench != null) { "execution requires a repository bench manifest or explicit profile; legacy solver recipes must be migrated" }
    }

    /** Refuse a submitted experiment or schedule name the lab would read as a scheduled run (`@`) or a confirmation
     *  ([Confirm.SUFFIX]): those are the names the lab gives its own jobs, and they change how a job is placed. */
    fun requirePlainName(name: String) {
        require(name.isNotBlank()) { "name is required" }
        require('@' !in name && '~' !in name) { "name must not contain '@' or '~': the lab reserves them for scheduled runs and confirmations" }
    }

    /** Refuse a spec the runner could not turn into commands; the message names the first problem. */
    fun validate(spec: ExperimentSpec, maxParallel: Int, cores: Int = Int.MAX_VALUE) {
        require(spec.name.isNotBlank()) { "name is required" }
        NodeTags.validate(spec.nodeTags)
        if (!spec.isAnalysis) Repositories.requireAllowed(spec.repository)
        requireExecutable(spec)
        spec.bench?.let { bench ->
            require(spec.profile == null && spec.analyze == null && !spec.isScript && !spec.measuresCli)
            require(spec.problems.isEmpty() && spec.problemList.isEmpty() && spec.seeds.isEmpty()) {
                "bench owns selection and seed expansion; submit opaque parameters in bench.request"
            }
            require(spec.repeats in 1..MAX_REPEATS)
            require(spec.configs.isNotEmpty() && spec.grid.values.none { it.isEmpty() })
            spec.grid.values.fold(spec.configs.size.toLong()) { count, values ->
                require(count <= 256 && values.size <= 256 && count * values.size <= 256) { "bench grid expands beyond 256 arms" }
                count * values.size
            }
            val arms = arms(spec)
            require(bench.replayJobs.values.all { it > 0 })
            require(bench.replayJobs.isEmpty() || bench.frozenCases.isNotEmpty() && bench.replayJobs.keys == arms.map { it.label }.toSet())
            require(arms.size in 1..256 && arms.map { it.label }.distinct().size == arms.size) { "bench arm labels must be unique and bounded" }
            require(bench.frozenCases.map(BenchRuns::key).distinct().size == bench.frozenCases.size)
            for (arm in arms) {
                require(arm.ref.isNotBlank() && !arm.ref.startsWith('-') && arm.ref.none(Char::isISOControl))
                val reference = arm.values["backend"] == REFERENCE_BACKEND
                val keys = if (reference) setOf("ref", "backend", "solver", "timeout", "processors", "fixed") else setOf("ref")
                require(arm.values.keys.all { key -> key in keys || !reference && key.startsWith("param.") && key.length > 6 }) {
                    "bench arms take ref and param.<opaque request key>; reference arms take backend, solver, timeout, processors and fixed"
                }
                require(arm.values.values.none { value -> value.any(Char::isISOControl) })
                if (reference) {
                    require(!arm.values["solver"].isNullOrBlank()) { "reference arms need a solver" }
                    require(arm.timeoutMs in 1..300_000_000 && (arm.values["processors"] == null || arm.values["processors"]?.toIntOrNull() in 1..1024))
                    require(arm.values["fixed"] == null || arm.values["fixed"]?.toBooleanStrictOrNull() != null)
                }
            }
            require(spec.parallel == null || spec.parallel in 1..maxParallel)
            return
        }
        requireOptions(spec)
        require(!spec.isScript) { "script jobs are retired: the lab runs only committed code; name a main to run with \"profile\" instead" }
        spec.analyze?.let { analyze ->
            require(analyze.kind in AnalyzeSpec.KINDS) { "analyze.kind must be one of ${AnalyzeSpec.KINDS}, not '${analyze.kind}'" }
            require(analyze.kind != AnalyzeSpec.CHECK || analyze.archive == null) { "a recheck names a job, not an archive" }
            require((analyze.job == null) != (analyze.archive == null)) { "analyze names a job or an archive, one of them" }
            analyze.job?.let { require(it > 0) { "analyze.job must name a job" } }
            if (analyze.archive == null) {
                require(analyze.sha256 == null && analyze.manifest == null && analyze.manifestSha256 == null) {
                    "analyze.sha256 and analyze.manifest pin an archive: name one with analyze.archive"
                }
            } else {
                require(analyze.sha256 != null && SHA256.matches(analyze.sha256)) {
                    "analyze.sha256 must pin the archive: its SHA-256 as 64 lowercase hex digits"
                }
                require(listOfNotNull(analyze.archive, analyze.manifest).none { "://" in it }) {
                    "analyze.archive and analyze.manifest name files under the lab's archive directory (\$LAB_DATA/archives), not URLs"
                }
            }
            require(
                spec.profile == null && spec.problems.isEmpty() && spec.problemList.isEmpty() && spec.base.isEmpty() &&
                    spec.configs == listOf(emptyMap<String, String>()) && spec.grid.isEmpty() && spec.seeds.isEmpty() &&
                    spec.repeats == 1 && !spec.measuresCli,
            ) { "an analyze job reruns the lab's analyser over another job's captures: no problems, configs, profile or profiling of its own" }
            return
        }
        spec.profile?.let { profile ->
            profile.runTimeoutSec?.let { timeout ->
                require(timeout in 1..(Long.MAX_VALUE / 1_000_000_000)) { "profile.runTimeoutSec must be positive and fit a monotonic deadline" }
            }
            ProfileRuns.validate(spec.repository, profile)
            require(profile.args.none { '\u0000' in it }) { "profile.args must not hold NUL" }
            arms(spec).single().values[DEBUG_KEY]?.let { require(it.toBooleanStrictOrNull() != null) { "debug must be true or false" } }
            val asked = arms(spec).singleOrNull()?.values?.get("timeout")
            require(asked == null || asked.toLongOrNull()?.let { it > 0 } == true) {
                "timeout must be a positive number of ms: a profile job's time limit, ${duration(PROFILE_TIMEOUT_MS)} " +
                    "unless it says, capped at ${duration(PROFILE_MAX_TIMEOUT_MS)}"
            }
            require(spec.problems.isEmpty() && spec.problemList.isEmpty()) { "a profile job selects no problems" }
            require(spec.configs.size == 1 && spec.grid.isEmpty() && spec.seeds.isEmpty() && spec.repeats == 1) {
                "a profile job runs its main once: no configs, grid, seeds or repeats"
            }
            require(!spec.measuresCli) { "measurement selects experiment CLI recording; a profile entry point controls its own recordings" }
            arms(spec).single().let { require(it.cores <= cores) { "the profile asks for ${it.cores} processors; the lab has $cores cores" } }
            return
        }
        error("execution requires a bench manifest or explicit profile")
    }

    /** The arm values a reference case takes: the solver, its budget, workers and whether it keeps to the model's
     *  search. Repository options mean nothing to a reference solver. */
    private val REFERENCE_KEYS = listOf("solver", "timeout", "processors", "fixed")

    /** The command that runs one reference case with the lab's own [launcher] ([com.eignex.lab.reference.ReferenceCli]),
     *  on the files `select files=true` resolved for [problem], writing its record under `$JOB_DIR/cases/<index>`. */
    fun referenceCommand(launcher: String, problem: Problem, arm: Arm, seed: Long?, index: Int): String {
        val model = requireNotNull(problem.model) { "no resolved files for ${problem.suite}/${problem.problem}" }
        val args = buildList {
            add("problem=${problem.referenceKey.second}")
            add("format=${problem.format}")
            add("model=$model")
            problem.data?.let { add("data=$it") }
            problem.includeDirs.forEach { add("include=$it") }
            for (key in REFERENCE_KEYS) arm.values[key]?.let { add("$key=$it") }
            if (seed != null) add("solver-seed=$seed")
        }.joinToString(" ") { quote(it) }
        return "exec ${quote(launcher)} reference $args out=\"\$JOB_DIR/cases/$index\""
    }

    /** How long a case may run: the solver's own budget, the bench's hard kill at twice it, and room for the JVMs. */
    fun caseTimeoutSec(arm: Arm): Long = arm.timeoutMs * 2 / 1000 + CASE_OVERHEAD_SEC

    /**
     * A profile job's time limit: its `timeout`, ten minutes when it gives none, and never over two hours, whatever it
     * asks. The one place the default and the cap are applied; [profileCaps] says when a request was cut down.
     */
    fun profileLimitMs(arm: Arm): Long =
        arm.values["timeout"]?.toLongOrNull()?.coerceAtMost(PROFILE_MAX_TIMEOUT_MS) ?: PROFILE_TIMEOUT_MS

    /**
     * How long a profile job's case may run before the lab stops it: its [profileLimitMs], plus the room a case gets
     * for its JVM. At this limit it is sent SIGTERM, and SIGKILL [STOP_GRACE_SEC] later if it is still running.
     */
    fun profileTimeoutSec(arm: Arm): Long = profileLimitMs(arm) / 1000 + CASE_OVERHEAD_SEC

    /** Active-time ceiling shared by worker preparation and execution; queue time is excluded. */
    fun runTimeoutSec(spec: ExperimentSpec): Long? = spec.bench?.runTimeoutSec ?: spec.profile?.let(::profileRunTimeoutSec)

    /** A local profile's whole-run limit ([ProfileMain.runTimeoutSec]), capped as its time limit is; null without one. */
    fun profileRunTimeoutSec(profile: ProfileMain): Long? = profile.runTimeoutSec?.coerceAtMost(PROFILE_MAX_TIMEOUT_MS / 1000)

    /** A profile job's time limit in words, for its page and its log: how long, and whether it is the default. */
    fun profileLimitText(spec: ExperimentSpec): String {
        val arm = arms(spec).single()
        val limit = duration(profileLimitMs(arm))
        return if ("timeout" in arm.values) limit else "$limit, the default"
    }

    /** What [spec] asked for over the profile cap, each with what it got instead; empty when nothing was. */
    fun profileCaps(spec: ExperimentSpec): List<String> {
        val profile = spec.profile ?: return emptyList()
        val asked = arms(spec).single().values["timeout"]?.toLongOrNull()
        return listOfNotNull(
            asked?.takeIf { it > PROFILE_MAX_TIMEOUT_MS }?.let {
                "a timeout of ${duration(it)} was asked, capped at ${duration(PROFILE_MAX_TIMEOUT_MS)}"
            },
            profile.runTimeoutSec?.takeIf { it > PROFILE_MAX_TIMEOUT_MS / 1000 }?.let {
                "a runTimeoutSec of $it s was asked, capped at ${PROFILE_MAX_TIMEOUT_MS / 1000} s"
            },
        )
    }

    /** [ms] in words: in hours when it is whole hours, in minutes when whole minutes, in seconds otherwise. */
    fun duration(ms: Long): String = when {
        ms % (MS_PER_MIN * 60) == 0L -> "${ms / (MS_PER_MIN * 60)} h"
        ms % MS_PER_MIN == 0L -> "${ms / MS_PER_MIN} min"
        else -> "${ms / 1000} s"
    }

    /** The problem a profile job's single case stands for: it runs no problem, and contributes no reference row. */
    fun profileProblem(spec: ExperimentSpec) = Problem("profile", spec.name)

    /**
     * The command of a profile job's case: [profile]'s main on the classpath of the bench built at [worktree], its
     * arguments quoted one by one, recording JFR to `$OUT/profile.jfr`. `$OUT` (whose files come back with the job's),
     * `$LAB_CORPUS` and `$LAB_WORKTREE` are set for it to read.
     */
    fun profileCommand(worktree: String, profile: ProfileMain, index: Int, corpus: String, debug: Boolean = true,
        repository: String = Repositories.DEFAULT): String =
        ProfileRuns.command(worktree, repository, profile, index, corpus, debug)

    /** Hours [cases] take, each using its full budget, run [parallel] at once within [cores]: the cost of a run where
     *  nothing solves early. The tighter of the two limits sets it. */
    fun estimateHours(cases: List<Case>, arms: List<Arm>, parallel: Int, cores: Int = Int.MAX_VALUE): Double {
        val byCount = cases.sumOf { arms[it.arm].timeoutMs } / parallel.toDouble()
        val byCores = cases.sumOf { arms[it.arm].timeoutMs * arms[it.arm].cores } / cores.toDouble()
        return maxOf(byCount, byCores) / MS_PER_HOUR
    }

    /** What [ExperimentSpec.compilationRecording] may be: JFR alone, or JFR and HotSpot's compilation log. */
    const val COMPILATION_JFR = "jfr"
    const val COMPILATION_JFR_LOG = "jfr+log"

    /** Historical debug flag, read from retained case metadata. */
    const val DEBUG_KEY = "debug"
    /** The arm keys only a reference arm (`backend=reference`) takes: its solver and whether it keeps to the model's search. */
    private const val CASE_OVERHEAD_SEC = 120L
    /** A profile job's time limit when it gives no `timeout`. */
    const val PROFILE_TIMEOUT_MS = 600_000L
    /** The most time a profile job gets, whatever it asks, as its time limit and as its whole-run limit. */
    const val PROFILE_MAX_TIMEOUT_MS = 7_200_000L
    private const val MS_PER_MIN = 60_000L
    /**
     * How long a case the lab stops at its time limit has between SIGTERM and SIGKILL: what `timeout -k 30` gives one
     * on AWS, and time enough for a JVM's shutdown hooks, JFR's dump on exit among them.
     */
    const val STOP_GRACE_SEC = 30L
    private val SHA256 = Regex("[0-9a-f]{64}")
    /** An option's name as the bench reads it. */
    private val OPTION = Regex("[A-Za-z][A-Za-z0-9._-]*")
    /** Options the lab sets for each case itself, which an arm or a selection may not: what to solve and where its
     *  record goes. */
    private val RESERVED = setOf("suite", "problem", "out", "solver-seed")

    /**
     * Refuse options the bench should not take from a spec: a name that is not plain, one the lab sets itself
     * ([RESERVED]), or a value with a control character. They reach the bench quoted, so no shell sees them; this keeps
     * a spec from choosing what a case solves or where it writes.
     */
    private fun requireOptions(spec: ExperimentSpec) {
        val arms = listOf(spec.base) + spec.configs + spec.grid.keys.map { mapOf(it to "") } + spec.grid.values.flatten().map { mapOf("value" to it) }
        for ((key, value) in arms.flatMap { it.entries }) {
            require(OPTION.matches(key) && key !in RESERVED) { "option '$key' is not one an experiment may set" }
            require(value.none { it.isISOControl() }) { "option '$key' has a control character" }
        }
        for ((key, value) in spec.problems.flatMap { it.entries }) {
            require(OPTION.matches(key) && key != "out") { "selection key '$key' is not one an experiment may set" }
            require(value.none { it.isISOControl() }) { "selection key '$key' has a control character" }
        }
    }
    private const val FINGERPRINT_BYTES = 6
    private const val MAX_REPEATS = 100
    private const val MS_PER_HOUR = 3_600_000.0
}

/** Reads `problems` as one selection or a list of them; writes a list. */
object SelectionsSerializer : JsonTransformingSerializer<List<Map<String, String>>>(
    ListSerializer(MapSerializer(String.serializer(), String.serializer())),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        element as? JsonArray ?: JsonArray(listOf(element))
}

/**
 * Which problems a selection keeps by their reference verdict. A problem the reference never ran is kept by [DECIDED]:
 * there is nothing to judge it by, and dropping it would empty a collection no reference has reached yet.
 */
@Serializable
enum class ReferenceFilterMode {
    /**
     * Leave out the problems the reference left undecided, or took over [REFERENCE_SLOW_MS] to decide: too hard to
     * measure a solver on.
     */
    @SerialName("decided") DECIDED,
    /** Keep only the problems the reference proved within [REFERENCE_SLOW_MS]: an optimum, infeasibility, or a satisfied decision problem. */
    @SerialName("proven") PROVEN,
    /** Keep only the problems with no reference verdict at all: what a reference run backfills. */
    @SerialName("missing") MISSING,
    /**
     * Keep the problems with no reference verdict, or an unproven one from a smaller budget than this run's: what a
     * scheduled reference run reruns, so it upgrades short-budget verdicts once and leaves hopeless problems alone.
     */
    @SerialName("unsettled") UNSETTLED,
    @SerialName("any") ANY;

    /** Whether a run with [budgetMs] per case keeps a problem whose reference verdict is [reference], null when it has none. */
    fun keeps(reference: Reference?, budgetMs: Long): Boolean = when (this) {
        DECIDED -> reference == null || reference.feasible != null && reference.elapsedMs <= REFERENCE_SLOW_MS
        PROVEN -> reference != null && reference.proven && reference.elapsedMs <= REFERENCE_SLOW_MS
        MISSING -> reference == null
        UNSETTLED -> reference == null || !reference.proven && reference.budgetMs < budgetMs
        ANY -> true
    }
}

/** Round-robin merge: the first of each list, then the second of each, and so on. */
fun <T> interleave(lists: List<List<T>>): List<T> =
    (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { k -> lists.mapNotNull { it.getOrNull(k) } }

const val DEFAULT_REF = "main"

/** A reference verdict slower than this marks a problem too hard for `decided` and `proven` to keep. */
const val REFERENCE_SLOW_MS = 5_000L
private const val DEFAULT_TIMEOUT_MS = 60_000L
