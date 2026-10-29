package com.eignex.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
    val host: HostPlacement = HostPlacement.LOCAL,
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

/** What an analysis job runs over, read from its [AnalyzeSpec] once [Experiments.validate] has accepted it. */
sealed interface AnalyzeTarget {
    /** Ended job [job]'s retained captures, by the analyser of [kind]. */
    data class OfJob(val job: Long, val kind: Kind) : AnalyzeTarget

    /** The captures in a pinned saved-capture [archive], by the compilation analyser. */
    data class OfArchive(val archive: String, val sha256: String, val manifest: String?, val manifestSha256: String?) : AnalyzeTarget

    enum class Kind(val word: String) {
        COMPILATION(AnalyzeSpec.COMPILATION), CHECK(AnalyzeSpec.CHECK);

        override fun toString() = word

        companion object {
            fun of(word: String): Kind = entries.firstOrNull { it.word == word } ?: throw IllegalArgumentException("unknown analysis kind $word")
        }
    }
}

/** [this] as what it names: a job or a pinned archive, never both or neither. */
val AnalyzeSpec.target: AnalyzeTarget get() = when {
    archive != null -> AnalyzeTarget.OfArchive(archive, requireNotNull(sha256) { "an archive is pinned by its sha256" }, manifest, manifestSha256)
    else -> AnalyzeTarget.OfJob(requireNotNull(job) { "analyze names a job or an archive" }, AnalyzeTarget.Kind.of(kind))
}

/** What a profile job runs: a committed JVM main on its classpath, or a committed executable. */
sealed interface ProfileEntry {
    data class JvmMain(val main: String, val classpath: List<String>, val jvmArgs: List<String>) : ProfileEntry
    data class Script(val script: String, val outputArgument: String?) : ProfileEntry
}

/** [this] as what it runs, once [ProfileRuns.validate] has accepted it. */
val ProfileMain.entry: ProfileEntry get() = script?.let { ProfileEntry.Script(it, outputArgument) } ?: ProfileEntry.JvmMain(main, classpath, jvmArgs)

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
    val referenceKey: ProblemKey get() = ProblemKey.of(this)
}

/** The case at one index of an experiment's commands. */
data class Case(val problem: Int, val arm: Int, val seed: Long?, val repeat: Int = 0)

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

/**
 * The (collection, problem) a reference row, a feature row and a trend entry are kept under. A bench case's own
 * `problem` attribute names it when it has one, so a case is matched with the references of the instance it ran.
 */
@Serializable
data class ProblemKey(val collection: String, val problem: String) {
    override fun toString() = "$collection/$problem"

    companion object {
        fun of(problem: Problem) = ProblemKey(problem.collection, problem.benchCase?.attributes?.get("problem") ?: problem.problem)
    }
}
