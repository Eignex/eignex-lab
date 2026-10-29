package com.eignex.lab

import java.security.MessageDigest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** What the lab does with an [ExperimentSpec]: validate it, expand it into arms, problems and cases, and split it. */
object Experiments {
    /** The local machine, a cloud node, and whichever is free first ([HostPlacement]). */
    val LAB_HOST = HostPlacement.LOCAL
    val AWS_HOST = HostPlacement.CLOUD
    val AUTO_HOST = HostPlacement.AUTO

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
            job.status.isActive && !job.cancelRequested &&
                job.experiment.let { !exemptFromSplitting(it) && splitKey(it) == key } == true
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
            add("problem=${problem.referenceKey.problem}")
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

/** Round-robin merge: the first of each list, then the second of each, and so on. */
fun <T> interleave(lists: List<List<T>>): List<T> =
    (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { k -> lists.mapNotNull { it.getOrNull(k) } }

const val DEFAULT_REF = "main"

/** A reference verdict slower than this marks a problem too hard for `decided` and `proven` to keep. */
const val REFERENCE_SLOW_MS = 5_000L
internal const val DEFAULT_TIMEOUT_MS = 60_000L
