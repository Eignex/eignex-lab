package com.eignex.lab

import java.security.MessageDigest
import kotlinx.serialization.Serializable
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
 * `main`) and `label` (its name in results); `param.<name>` becomes `param=<name>=<value>`.
 */
@Serializable
data class ExperimentSpec(
    val name: String,
    /** The problem selections; none for a script job. */
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
     * Where the cases run: `lab`, the lab machine, or `aws`, EC2 instances the lab launches for the job. A submitted
     * experiment that leaves it out runs on AWS when it can there; a schedule's runs stay on the lab machine.
     */
    val host: String = Experiments.LAB_HOST,
    /** For `aws`: how many instances to split the job over, by problem; unset, as many as are free. */
    val machines: Int? = null,
    /** Exact problems, as a plan recorded them, run instead of selecting: what a confirmation job ([Confirm]) reruns. */
    val problemList: List<Problem> = emptyList(),
    /** What the experiment tests and why, in a sentence or two: the queue, the history and the job page show it. */
    val description: String = "",
    /** AWS-only whole-CLI JFR and physical resource measurements, returned with each case's files. */
    val profileCli: Boolean = false,
    /**
     * A script job: this bash script, run once in the worktree of the base `ref` after the build, instead of
     * problems and arms. For measurements and checks the experiments cannot express; not for running the build's
     * tests or lint gates, which CI runs. See [isScript].
     */
    val script: String? = null,
    /** A script job's script as a path in the repository at the base `ref`, instead of [script]. */
    val scriptFile: String? = null,
)

/** Whether [this] is a script job rather than an experiment over problems. */
val ExperimentSpec.isScript: Boolean get() = !script.isNullOrBlank() || !scriptFile.isNullOrBlank()

/** One configuration of an experiment: its [label] and the solve arguments, `ref` included. */
@Serializable
data class Arm(val label: String, val values: Map<String, String>) {
    val ref: String get() = values["ref"] ?: DEFAULT_REF
    val timeoutMs: Long get() = values["timeout"]?.toLong() ?: DEFAULT_TIMEOUT_MS

    /** Cores each of the arm's solves keeps busy: its portfolio's `processors`, one without. */
    val cores: Int get() = values["processors"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
}

/** One problem as `klause-bench select` names it. */
@Serializable
data class Problem(
    val suite: String,
    val problem: String,
    val collection: String = "",
    val family: String = "",
    val format: String = "",
    val category: String = "",
)

/** The case at one index of an experiment's commands. */
data class Case(val problem: Int, val arm: Int, val seed: Long?, val repeat: Int = 0)

object Experiments {
    const val LAB_HOST = "lab"
    const val AWS_HOST = "aws"
    /**
     * What [spec] measures, as a short hash: its problems, arms, seeds and repeats. Its name, the ref a schedule pins
     * on each run, and how it is run (parallel, priority, confirm) are left out, so every run of an unchanged schedule
     * shares one fingerprint and an edited one starts another.
     */
    fun fingerprint(spec: ExperimentSpec): String {
        val measured = spec.copy(
            name = "",
            description = "",
            problems = spec.problems.map { it.toSortedMap() },
            base = (spec.base - "ref").toSortedMap(),
            configs = spec.configs.map { it.toSortedMap() },
            grid = spec.grid.toSortedMap(),
            parallel = null,
            machines = null,
            priority = 0,
            confirm = false,
        )
        return hash(Json.encodeToString(ExperimentSpec.serializer(), measured))
    }

    /** What a run of [spec] measured over [problems], the ones it planned: its [fingerprint] and its problem set,
     *  so a named set redrawn or a selection the reference filter shifts tells runs apart as an edited spec does. */
    fun fingerprint(spec: ExperimentSpec, problems: Collection<Problem>): String =
        hash(fingerprint(spec) + "\n" + problems.map { "${it.suite}/${it.problem}" }.sorted().joinToString("\n"))

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

    /** Refuse a spec the runner could not turn into commands; the message names the first problem. */
    fun validate(spec: ExperimentSpec, maxParallel: Int, cores: Int = Int.MAX_VALUE) {
        require(spec.name.isNotBlank()) { "name is required" }
        if (spec.isScript) {
            require(spec.script.isNullOrBlank() != spec.scriptFile.isNullOrBlank()) { "a script job names one of script and scriptFile" }
            require(spec.problems.isEmpty() && spec.problemList.isEmpty()) { "a script job selects no problems" }
            require(spec.configs.size == 1 && spec.grid.isEmpty() && spec.seeds.isEmpty() && spec.repeats == 1) {
                "a script job runs its script once: no configs, grid, seeds or repeats"
            }
            require(!spec.profileCli) { "profileCli is for experiments" }
            arms(spec).single().let { require(it.cores <= cores) { "the script asks for ${it.cores} processors; the lab has $cores cores" } }
            return
        }
        require(!spec.profileCli || spec.host == AWS_HOST) { "profileCli requires host=aws" }
        require(!spec.profileCli || spec.parallel == 1) { "profileCli requires parallel=1" }
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
     *  `$JOB_DIR/cases/<index>`. */
    fun command(worktree: String, problem: Problem, arm: Arm, seed: Long?, index: Int, corpus: String): String {
        val args = buildList {
            add("suite=${problem.suite}")
            add("problem=${problem.problem}")
            for ((key, value) in arm.values.toSortedMap()) {
                when {
                    key == "ref" -> Unit
                    key.startsWith(PARAM) -> add("param=${key.removePrefix(PARAM)}=$value")
                    else -> add("$key=$value")
                }
            }
            if (seed != null) add("solver-seed=$seed")
        }.joinToString(" ") { quote(it) }
        // The result cache would replay an earlier run's timings: a case always solves. The corpus cap stays off so
        // no case evicts a collection another case is reading.
        val opts = "-Dklause.bench.cache=false -Dklause.bench.corpusCache=$corpus -Dklause.workspace.root=$worktree"
        return "cd ${quote("$worktree/klause-bench")} && " +
            "if [ -f build/provenance.json ]; then export KLAUSE_BENCH_PROVENANCE=\"\$PWD/build/provenance.json\"; " +
            "else unset KLAUSE_BENCH_PROVENANCE; fi && JAVA_OPTS=${quote(opts)} KLAUSE_BENCH_CORPUS_MAX_GB=off " +
            "exec ./build/install/klause-bench/bin/klause-bench solve-one $args out=\"\$JOB_DIR/cases/$index\""
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

    /** How long a script job's script may run: its `timeout` (an hour unless it says), plus the room a case gets. */
    fun scriptTimeoutSec(arm: Arm): Long = (arm.values["timeout"]?.toLongOrNull() ?: SCRIPT_TIMEOUT_MS) / 1000 + CASE_OVERHEAD_SEC

    /** The problem a script job's single case stands for: it runs no problem, and contributes no reference row. */
    fun scriptProblem(spec: ExperimentSpec) = Problem("script", spec.name)

    /**
     * The command of a script job's case: [spec]'s script in [worktree], with `$OUT` (where files it keeps go, read
     * back with the job's files), the corpus cache, and the built CLI and bench, as `$KLAUSE_CLI` and `$KLAUSE_BENCH`.
     */
    fun scriptCommand(worktree: String, spec: ExperimentSpec, index: Int, corpus: String): String {
        val run = spec.scriptFile?.takeIf { it.isNotBlank() }?.let { "bash ${quote("$worktree/$it")}" }
            ?: "bash -c ${quote(checkNotNull(spec.script))}"
        return "cd ${quote(worktree)} && export OUT=\"\$JOB_DIR/cases/$index/out\" KLAUSE_CORPUS=${quote(corpus)} " +
            "KLAUSE_CLI=${quote("$worktree/klause-cli/build/install/klause-cli-jvm/bin/klause-cli")} " +
            "KLAUSE_BENCH=${quote("$worktree/klause-bench/build/install/klause-bench/bin/klause-bench")} && " +
            "mkdir -p \"\$OUT\" && exec $run"
    }

    /** Hours [cases] take, each using its full budget, run [parallel] at once within [cores]: the cost of a run where
     *  nothing solves early. The tighter of the two limits sets it. */
    fun estimateHours(cases: List<Case>, arms: List<Arm>, parallel: Int, cores: Int = Int.MAX_VALUE): Double {
        val byCount = cases.sumOf { arms[it.arm].timeoutMs } / parallel.toDouble()
        val byCores = cases.sumOf { arms[it.arm].timeoutMs * arms[it.arm].cores } / cores.toDouble()
        return maxOf(byCount, byCores) / MS_PER_HOUR
    }

    private fun requireValue(key: String, value: String) =
        require(value.isNotBlank() && value.none { it.isWhitespace() || it.isISOControl() }) { "'$key' has an empty value or whitespace in it" }

    private const val PARAM = "param."
    private val NAME = Regex("[A-Za-z0-9._-]+")
    private const val REFERENCE_KEY = "reference"
    private const val UNCAPPED = 1_000_000
    private val PROBLEM_KEYS = setOf("suite", "set", "kind", "category", "tag", "name", "per-family", "max", "seed", "balance", REFERENCE_KEY)
    private val ARM_KEYS = setOf("ref", "label", "timeout", "backend", "solver", "engine", "processors", "lp", "presolve", "fixed", "exact")
    private const val CASE_OVERHEAD_SEC = 120L
    private const val SCRIPT_TIMEOUT_MS = 3_600_000L
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
