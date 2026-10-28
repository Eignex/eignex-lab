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

/**
 * An experiment: one selection of problems, solved by every configuration it expands to, once per seed. Each
 * (problem, configuration, seed) is a case, and a case is one command the lab writes itself: a `klause-bench
 * solve-one` in the worktree of that configuration's commit.
 *
 * [problems] are bench selection filters (`suite`, `kind`, `per-family`, …), or a list of such selections whose
 * problems are pooled, each selection capped on its own. Each entry of [configs] is merged over
 * [base], and each result is crossed with every combination of [grid]'s values, so an A/B is a grid of one axis
 * with two values. A configuration's keys are bench solve arguments plus `ref` (the commit to build, default
 * `main`), `label` (its name in results) and `debug` (the CLI's `KLAUSE_DEBUG`, default `true`, [Arm.debug]);
 * `param.<name>` becomes `param=<name>=<value>`.
 */
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
     * Where the cases may run, as the lab placed the job ([Placement]); a submitted value is ignored. `lab`, the local
     * machine alone; `aws`, a cloud node alone (Nomad workers); `auto`, whichever of the local machine and a node is free
     * first. Once taken it is where the job went.
     */
    val host: String = Experiments.LAB_HOST,
    /** Set when an `auto` job is taken: its [host] is then where it went. On the local machine it gives way to any job
     *  placed on the local machine alone. */
    val auto: Boolean = false,
    /** Retired: the cloud node pool decides how many nodes work on a job. Read so older specs still parse; ignored. */
    val machines: Int? = null,
    /** Exact problems, as a plan recorded them, run instead of selecting: what a confirmation job ([Confirm]) reruns. */
    val problemList: List<Problem> = emptyList(),
    /** What the experiment tests and why, in a sentence or two: the queue, the history and the job page show it. */
    val description: String = "",
    /** One recording mode for experiment CLI cases; omitted for ordinary runs. */
    val measurement: Measurement = Measurement.NONE,
    /**
     * An analysis job: the lab's own analyser run again over an ended job's retained captures, writing fresh reports.
     * Nothing is built and no solver or klause code runs. Its spec names nothing else.
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
    /** Optional required and excluded node tags. Omitted on ordinary jobs. */
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
    /** Gradle tasks that prepare this entry point; an empty list retains the legacy Klause build for a JVM main. */
    val buildTasks: List<String> = emptyList(),
    /** Repository-relative runtime entries, optionally naming a directory of jars. Required for JVM mains outside Klause. */
    val classpath: List<String> = emptyList(),
    /** JVM options for a main; a script controls its own runtimes and recordings. */
    val jvmArgs: List<String> = emptyList(),
    /** Pass this option followed by $OUT/report to a script that publishes a report directory, such as Koblas. */
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

    /**
     * Whether the arm's CLI runs with `KLAUSE_DEBUG=true`, its lightweight runtime statistics on: the arm's `debug`,
     * true unless it says `false`. Whole-CLI recording ([ExperimentSpec.measurement]) does not depend on it.
     */
    val debug: Boolean get() = values[Experiments.DEBUG_KEY]?.toBooleanStrictOrNull() ?: true
}

/** One problem as `klause-bench select` names it, with the [model] and [data] files and MiniZinc [includeDirs] that
 *  `select files=true` resolves, which a reference arm's cases solve without the bench. */
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
)

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
     * shares one fingerprint and an edited one starts another. Where it runs ([ExperimentSpec.host] and
     * [ExperimentSpec.auto]) is left out too: a schedule moved between the local machine and the cloud, or a run taken
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
            machines = null,
            priority = 0,
            confirm = false,
            host = LAB_HOST,
            auto = false,
            profile = spec.profile?.copy(runTimeoutSec = null),
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

    /** Refuse a spec the runner could not turn into commands; the message names the first problem. */
    fun validate(spec: ExperimentSpec, maxParallel: Int, cores: Int = Int.MAX_VALUE) {
        require(spec.name.isNotBlank()) { "name is required" }
        NodeTags.validate(spec.nodeTags)
        Repositories.requireAllowed(spec.repository)
        require(spec.repository == Repositories.DEFAULT || spec.isProfile || spec.bench != null) { "other Eignex repositories require a profile or bench entry point" }
        spec.bench?.let {
            require(spec.profile == null && spec.analyze == null && !spec.isScript && !spec.measuresCli)
            require(spec.problems.isEmpty() && spec.problemList.isEmpty() && spec.seeds.isEmpty() && spec.grid.isEmpty() &&
                spec.configs == listOf(emptyMap<String, String>()) && spec.repeats == 1) {
                "bench owns its cases and repetitions; submit its parameters in bench.request"
            }
            require(spec.base.keys.all { key -> key == "ref" || key == "label" }) { "bench owns runtime settings and resources" }
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
        require(spec.measurement != Measurement.RESOURCES || arms(spec).none { it.values["backend"] == REFERENCE_BACKEND }) {
            "measurement=resources measures the Klause CLI, not reference-backend arms"
        }
        require(spec.parallel == null || spec.parallel in 1..maxParallel) { "parallel must be between 1 and $maxParallel" }
        arms(spec).firstOrNull { it.cores > cores }?.let { require(false) { "arm '${it.label}' asks for ${it.cores} processors; the lab has $cores cores" } }
        require(spec.repeats in 1..MAX_REPEATS) { "repeats must be between 1 and $MAX_REPEATS" }
        require(spec.configs.isNotEmpty()) { "configs must not be empty" }
        require(spec.grid.values.none { it.isEmpty() }) { "every grid axis needs at least one value" }
        require(spec.problems.isNotEmpty() || spec.problemList.isNotEmpty()) { "problems must name at least one selection" }
        for (selection in spec.problems) {
            require(selection["suite"]?.isNotBlank() == true || selection["set"]?.isNotBlank() == true) {
                "every problems selection needs a suite or a set"
            }
            for ((key, value) in selection) {
                require(key in PROBLEM_KEYS) { "unknown problems key '$key' (have ${PROBLEM_KEYS.sorted()})" }
                requireValue(key, value)
                if (key == REFERENCE_KEY) {
                    require(ReferenceFilterMode.entries.any { it.name.equals(value, ignoreCase = true) }) {
                        "reference must be one of ${ReferenceFilterMode.entries.map { it.name.lowercase() }}"
                    }
                }
            }
        }
        for (arm in arms(spec)) {
            for ((key, value) in arm.values) {
                require(key in ARM_KEYS || key.startsWith(PARAM)) { "unknown config key '$key' (have ${ARM_KEYS.sorted()} and param.<name>)" }
                if (key.startsWith(PARAM)) require(NAME.matches(key.removePrefix(PARAM))) { "bad param name in '$key'" }
                requireValue(key, value)
            }
            require(!arm.ref.startsWith("-")) { "ref must not start with '-'" }
            require(arm.values["timeout"]?.toLongOrNull()?.let { it > 0 } ?: true) { "timeout must be a positive number of ms" }
            require(arm.values["exact"]?.toBooleanStrictOrNull() != null || "exact" !in arm.values) {
                "exact must be true or false"
            }
            val backend = arm.values["backend"]
            require(backend == null || backend == KLAUSE_BACKEND || backend == REFERENCE_BACKEND) {
                "backend must be $KLAUSE_BACKEND (the default) or $REFERENCE_BACKEND, not '$backend'"
            }
            for (key in REFERENCE_ONLY_KEYS) {
                require(key !in arm.values || backend == REFERENCE_BACKEND) { "'$key' is for reference arms; it needs backend=$REFERENCE_BACKEND" }
            }
            require(arm.values["fixed"]?.toBooleanStrictOrNull() != null || "fixed" !in arm.values) { "fixed must be true or false" }
            require(arm.values[DEBUG_KEY]?.toBooleanStrictOrNull() != null || DEBUG_KEY !in arm.values) { "debug must be true or false" }
        }
        require(arms(spec).map { it.label }.distinct().size == arms(spec).size) { "config labels must be unique" }
    }

    /** The bench arguments of one problems [selection]. */
    fun selectArgs(selection: Map<String, String>): String =
        selection.filterKeys { it != REFERENCE_KEY }.entries.joinToString(" ") { (key, value) -> quote("$key=$value") }

    /**
     * A warning when [selected] looks capped by its suite's own default rather than by [selection]: no `per-family`
     * was asked for, yet every family gave exactly one problem, which is what a suite that defaults to one per family
     * returns. Null otherwise.
     */
    fun defaultCapped(selection: Map<String, String>, selected: List<Problem>): String? {
        if ("per-family" in selection || "name" in selection || "set" in selection) return null
        val families = selected.groupingBy { it.family }.eachCount()
        if (families.size < 2 || families.values.any { it != 1 }) return null
        return "every one of ${families.size} families gave one problem, likely the suite's default of one per family; " +
            "set \"per-family\" to take more"
    }

    /** Whether a selection's caps are applied by the lab, after [mode] filters, rather than by the bench before it. A
     *  format-balanced selection keeps the bench's caps: the balance is the bench's to strike. */
    fun refills(mode: ReferenceFilterMode, selection: Map<String, String>): Boolean =
        mode != ReferenceFilterMode.ANY && ("per-family" in selection || "max" in selection) && "balance" !in selection

    /** [selection] with its caps lifted: no `max`, and a `per-family` too large to bind. A `seed` stays, so the bench
     *  still orders each family by its seeded shuffle, and the lab's cap takes the head of that order. */
    fun uncapped(selection: Map<String, String>): Map<String, String> =
        selection - "max" + if ("per-family" in selection) mapOf("per-family" to UNCAPPED.toString()) else emptyMap()

    /** The bench's caps over [problems] in the bench's order: at most [perFamily] per family, families round-robin, then
     *  at most [max] in all. */
    fun cap(problems: List<Problem>, perFamily: Int?, max: Int?): List<Problem> {
        val families = problems.groupBy { it.suite to it.family }.values.map { if (perFamily == null) it else it.take(perFamily) }
        val merged = interleave(families)
        return if (max == null) merged else merged.take(max)
    }

    /** What a selection's `reference` filter keeps, by default `decided`, or `any` when the experiment itself runs the
     *  reference solver, whose own verdicts are what it is there to produce, or the selection names a set, which was
     *  drawn with the reference and is meant to stay as it is. */
    fun referenceFilter(spec: ExperimentSpec, selection: Map<String, String>): ReferenceFilterMode =
        selection[REFERENCE_KEY]?.let { v -> ReferenceFilterMode.entries.first { it.name.equals(v, ignoreCase = true) } }
            ?: if ("set" in selection || arms(spec).any { it.values["backend"] == REFERENCE_BACKEND }) ReferenceFilterMode.ANY
            else ReferenceFilterMode.DECIDED

    /** The command that runs one case: `solve-one` from the bench built at [worktree], writing its record under
     *  `$JOB_DIR/cases/<index>`. Only Klause's own options ([BENCH_KEYS] and `param.*`) go to the bench: the solver
     *  choice (`backend`, `solver`, `fixed`), `ref` and `debug` are the lab's, and the source-validation request stays
     *  with the lab, which checks the case itself ([SourceValidation.forBench]). An arm with `debug=false` runs with
     *  `KLAUSE_DEBUG=false` ([debugEnv]), over the `true` every host sets for a case. */
    fun command(worktree: String, problem: Problem, arm: Arm, seed: Long?, index: Int, corpus: String): String {
        val args = buildList {
            add("suite=${problem.suite}")
            add("problem=${problem.problem}")
            for ((key, value) in SourceValidation.forBench(arm.values).toSortedMap()) {
                when {
                    key.startsWith(PARAM) -> add("param=${key.removePrefix(PARAM)}=$value")
                    key in BENCH_KEYS -> add("$key=$value")
                }
            }
            if (seed != null) add("solver-seed=$seed")
        }.joinToString(" ") { quote(it) }
        // An older bench keeps a result cache that would replay an earlier run's timings: a case always solves, and a
        // bench without the cache ignores the property. The corpus cap stays off so no case evicts a collection another
        // case is reading.
        val opts = "-Dklause.bench.cache=false -Dklause.bench.corpusCache=$corpus -Dklause.workspace.root=$worktree"
        return "cd ${quote("$worktree/klause-bench")} && " +
            "if [ -f build/provenance.json ]; then export KLAUSE_BENCH_PROVENANCE=\"\$PWD/build/provenance.json\"; " +
            "else unset KLAUSE_BENCH_PROVENANCE; fi && JAVA_OPTS=${quote(opts)} KLAUSE_BENCH_CORPUS_MAX_GB=off " +
            debugEnv(arm) + "exec ./build/install/klause-bench/bin/klause-bench solve-one $args out=\"\$JOB_DIR/cases/$index\""
    }

    /** The environment assignment a case of [arm] adds to its command: `KLAUSE_DEBUG=false` when the arm turned the
     *  CLI's debug statistics off, nothing otherwise, leaving the host's `KLAUSE_DEBUG=true`. */
    private fun debugEnv(arm: Arm): String = if (arm.debug) "" else "KLAUSE_DEBUG=false "

    /** The arm values a reference case takes: the solver, its budget, workers and whether it keeps to the model's
     *  search. Klause's own options mean nothing to a reference solver. */
    private val REFERENCE_KEYS = listOf("solver", "timeout", "processors", "fixed")

    /** The command that runs one reference case with the lab's own [launcher] ([com.eignex.lab.reference.ReferenceCli]),
     *  on the files `select files=true` resolved for [problem], writing its record under `$JOB_DIR/cases/<index>`. */
    fun referenceCommand(launcher: String, problem: Problem, arm: Arm, seed: Long?, index: Int): String {
        val model = requireNotNull(problem.model) { "no resolved files for ${problem.suite}/${problem.problem}" }
        val args = buildList {
            add("problem=${problem.problem}")
            add("format=${problem.format}")
            add("model=$model")
            problem.data?.let { add("data=$it") }
            problem.includeDirs.forEach { add("include=$it") }
            for (key in REFERENCE_KEYS) arm.values[key]?.let { add("$key=$it") }
            if (seed != null) add("solver-seed=$seed")
        }.joinToString(" ") { quote(it) }
        return "exec ${quote(launcher)} reference $args out=\"\$JOB_DIR/cases/$index\""
    }

    /**
     * A shell step writing the build's provenance manifest, for a bench that can: one that cannot leaves none, and a
     * failing capture fails the build. A bench's `--help` exits non-zero on many revisions, so only its text counts.
     */
    internal fun captureProvenance(worktree: String): String {
        val bench = quote("$worktree/klause-bench/build/install/klause-bench/bin/klause-bench")
        val manifest = quote("$worktree/klause-bench/build/provenance.json")
        return "(cd ${quote("$worktree/klause-bench")} && test -x $bench && rm -f $manifest && " +
            "help=\$($bench --help 2>&1 || true) && case \"\$help\" in " +
            "*'bench provenance out=<file>'*) $bench provenance ${quote("out=$worktree/klause-bench/build/provenance.json")} && test -s $manifest;; " +
            "*) echo 'bench revision has no provenance manifest support';; esac)"
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
     * `$KLAUSE_CORPUS`, `$KLAUSE_CLI` and `$KLAUSE_BENCH` are set for it to read.
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

    private fun requireValue(key: String, value: String) =
        require(value.isNotBlank() && value.none { it.isWhitespace() || it.isISOControl() }) { "'$key' has an empty value or whitespace in it" }

    /** What [ExperimentSpec.compilationRecording] may be: JFR alone, or JFR and HotSpot's compilation log. */
    const val COMPILATION_JFR = "jfr"
    const val COMPILATION_JFR_LOG = "jfr+log"

    private const val PARAM = "param."
    private val NAME = Regex("[A-Za-z0-9._-]+")
    private const val REFERENCE_KEY = "reference"
    private const val UNCAPPED = 1_000_000
    private val PROBLEM_KEYS = setOf("suite", "set", "kind", "category", "tag", "name", "per-family", "max", "seed", "balance", REFERENCE_KEY)
    /** The arm key that turns the CLI's `KLAUSE_DEBUG` off ([Arm.debug]); the lab's, never the bench's. */
    const val DEBUG_KEY = "debug"
    private val ARM_KEYS = setOf("ref", "label", "timeout", "backend", "solver", "engine", "processors", "lp", "presolve", "fixed", "exact", DEBUG_KEY)
    /** The arm keys `klause-bench solve-one` takes, besides `param.*`. */
    private val BENCH_KEYS = setOf("timeout", "engine", "processors", "lp", "presolve", "exact")
    /** The arm keys only a reference arm (`backend=reference`) takes: its solver and whether it keeps to the model's search. */
    private val REFERENCE_ONLY_KEYS = listOf("solver", "fixed")
    /** The `backend` a Klause arm may name; leaving it out means the same. */
    private const val KLAUSE_BACKEND = "klause"
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
enum class ReferenceFilterMode {
    /**
     * Leave out the problems the reference left undecided, or took over [REFERENCE_SLOW_MS] to decide: too hard to
     * measure klause on.
     */
    DECIDED,
    /** Keep only the problems the reference proved within [REFERENCE_SLOW_MS]: an optimum, infeasibility, or a satisfied decision problem. */
    PROVEN,
    /** Keep only the problems with no reference verdict at all: what a reference run backfills. */
    MISSING,
    /**
     * Keep the problems with no reference verdict, or an unproven one from a smaller budget than this run's: what a
     * scheduled reference run reruns, so it upgrades short-budget verdicts once and leaves hopeless problems alone.
     */
    UNSETTLED,
    ANY;

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
