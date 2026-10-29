package com.eignex.lab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.TimeUnit

/** What submitting an experiment came to ([ExperimentService.submit]). */
sealed interface Submission {
    /** Queued as a new job. */
    data class Queued(val created: Created) : Submission

    /** Refused for what it names: an analysis of captures that are gone, or of a job that has not ended. */
    data class Refused(val reason: String) : Submission

    /** A batch of an experiment still active, refused as one question split in two ([Experiments.splitOf]). */
    data class SplitConflict(val message: String) : Submission
}

/** Submitting experiments: the checks a submission passes, where the lab places it, and queueing it as a job. */
class ExperimentService internal constructor(private val services: ApiServices) {
    private val config get() = services.config
    private val store get() = services.store

    /**
     * Queue the experiment [input] describes, with defaults for an analysis ([analysisDefaults]). A malformed or
     * unplaceable one is refused as an [IllegalArgumentException]; one that conflicts with what the lab holds is a
     * [Submission.Refused] or a [Submission.SplitConflict].
     */
    suspend fun submit(input: JsonObject): Submission {
        // Public placement input is tags only; the lab records its resolved host in the stored recipe.
        val body = analysisDefaults(experimentInput(input))
        // Jobs eligible for the local machine are capped to it; cloud-only tag requests use compatible cloud capacity.
        val submitted = Json.decodeFromJsonElement(ExperimentSpec.serializer(), body).copy(capped = emptyList())
        Experiments.requirePlainName(submitted.name)
        val given = if (NodeTags.matches(submitted.nodeTags, config.localNodeTags))
            Experiments.capToLocal(submitted, config.cores, config.maxParallel) else submitted
        val spec = given.copy(host = services.placement.resolve(given, JobOrigin.Submitted(given.name), checked = false))
        requireDescription(spec)
        services.placement.requireFits(spec)
        val coreLimit = services.placement.coreLimit(spec)
        Experiments.validate(spec, if (spec.host == Experiments.AWS_HOST) coreLimit else config.maxParallel, coreLimit)
        spec.analyze?.let { return analysis(spec, it) }
        // One question is one experiment: a batch of an active one is refused ([Experiments.splitOf]).
        Experiments.splitOf(spec, store.active(withCommands = false))?.let { return Submission.SplitConflict(Experiments.splitMessage(it)) }
        val arms = Experiments.arms(spec)
        for (ref in arms.map { it.ref }.distinct()) requireRef(ref, Repositories.source(config, spec.repository), services.trust(spec.repository))
        val id = store.create(spec.name, arms.first().ref, emptyList(), spec.parallel ?: config.defaultParallel, spec.priority, spec)
        val notes = (if (spec.isProfile) {
            listOf("time limit: ${Experiments.profileLimitText(spec)}, at most ${Experiments.duration(Experiments.PROFILE_MAX_TIMEOUT_MS)}") +
                Experiments.profileCaps(spec).map { "capped: $it" }
        } else emptyList()).plus(spec.capped.map { "capped: $it" }).ifEmpty { null }
        return Submission.Queued(Created(id, notes, PlacementPolicy.note(spec.host), spec.host.word))
    }

    /** An analysis needs its job's captures: refused now, saying why, when they are gone or the job has not ended. */
    private suspend fun analysis(spec: ExperimentSpec, analyze: AnalyzeSpec): Submission {
        val target = analyze.target
        val refusal = withContext(Dispatchers.IO) {
            when (target) {
                is AnalyzeTarget.OfJob -> if (target.kind == AnalyzeTarget.Kind.CHECK) CaseChecks.refusal(store.job(target.job))
                    else CompilationReports.refusal(store.job(target.job), config.jobDir(target.job))
                is AnalyzeTarget.OfArchive -> CaptureArchives.refusal(analyze, config)
            }
        }
        val what = when (target) {
            is AnalyzeTarget.OfJob -> "job ${target.job}"
            is AnalyzeTarget.OfArchive -> "archive ${target.archive}"
        }
        if (refusal != null) return Submission.Refused("cannot analyse $what: $refusal")
        val id = store.create(spec.name, DEFAULT_REF, emptyList(), 1, spec.priority, spec)
        val note = when (target) {
            is AnalyzeTarget.OfJob -> if (target.kind == AnalyzeTarget.Kind.CHECK) "checks job ${target.job}'s solution claims again on the local machine; no solver runs"
                else "analyses job ${target.job}'s retained captures on the local machine; no solver runs"
            is AnalyzeTarget.OfArchive -> "checks the lab-local archive against its sha256, verifies it and analyses its captures on the local machine; no solver runs"
        }
        return Submission.Queued(Created(id, listOf(note), PlacementPolicy.note(spec.host), spec.host.word))
    }

    /**
     * Fail unless [ref] names a branch or tag on origin, and, for a branch, unless only trusted users pushed to it
     * ([Trust.requireBranch]): a refused branch is refused before it queues. A commit named by its sha is checked when
     * the job is planned, as every commit is, and so is a branch when GitHub cannot be asked now; an origin that does
     * not answer proves nothing, so the ref passes and the runner reports it when it resolves it.
     */
    internal suspend fun requireRef(ref: String, repoUrl: String, trust: Trust) {
        if (!Regex("[A-Za-z0-9._/-]+").matches(ref) || Regex("[0-9a-f]{7,40}").matches(ref)) return
        var branch = false
        val known = withContext(Dispatchers.IO) {
            val process = ProcessBuilder("git", "ls-remote", repoUrl, "refs/heads/$ref", "refs/tags/$ref")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .apply { environment()["GIT_TERMINAL_PROMPT"] = "0" }
                .start()
            // Waited on before reading: the answer is a line or two, well inside the pipe buffer, and a hung remote
            // must not hold the request past the timeout.
            if (!process.waitFor(LS_REMOTE_SEC, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                true
            } else {
                val listed = process.inputStream.bufferedReader().readText()
                branch = listed.lines().any { it.endsWith("refs/heads/$ref") }
                process.exitValue() != 0 || listed.isNotBlank()
            }
        }
        require(known) { "unknown ref: $ref (no such branch or tag on origin; is it pushed?)" }
        if (!branch) return
        val refusal = withContext(Dispatchers.IO) { runCatching { trust.requireBranch(ref) }.exceptionOrNull() }
        if (refusal is UntrustedCommit) throw refusal
    }

    private companion object {
        const val LS_REMOTE_SEC = 15L
    }
}

/** An experiment says what it is for, so the queue and the history read as more than names. */
internal fun requireDescription(spec: ExperimentSpec) = require(spec.description.isNotBlank()) {
    "description is required: a sentence or two on what the experiment tests and why, e.g. \"A/B of #2232's " +
        "continuation scheduler against main on the sweep set, to see whether it closes the uf250 gap\""
}

/**
 * [given] with an analysis job's name and description filled in when it gives none, so `{"analyze": {"job": 12}}`, or
 * `{"analyze": {"archive": …, "sha256": …}}`, is a whole submission; any other body as it is.
 */
internal fun analysisDefaults(given: JsonObject): JsonObject {
    // `{"recheck": {"job": N}}` is an analysis of kind `check` ([AnalyzeSpec.CHECK]).
    val body = (given["recheck"] as? JsonObject)?.let { recheck ->
        val job = recheck["job"] ?: return given
        val defaults = mapOf(
            "name" to kotlinx.serialization.json.JsonPrimitive("recheck-$job"),
            "description" to kotlinx.serialization.json.JsonPrimitive("Check job $job's solution claims again with the lab's source checkers"),
        )
        JsonObject(defaults.filterKeys { it !in given } + (given - "recheck") +
            ("analyze" to JsonObject(mapOf("job" to job, "kind" to kotlinx.serialization.json.JsonPrimitive(AnalyzeSpec.CHECK)))))
    } ?: given
    val analyze = body["analyze"] as? JsonObject ?: return body
    val job = (analyze["job"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    val archive = (analyze["archive"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    val sha = (analyze["sha256"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    val defaults = when {
        job != null -> mapOf(
            "name" to kotlinx.serialization.json.JsonPrimitive("analyze-$job"),
            "description" to kotlinx.serialization.json.JsonPrimitive("Rerun the lab's compilation analyser over job $job's retained captures"),
        )
        archive != null -> mapOf(
            "name" to kotlinx.serialization.json.JsonPrimitive("analyze-archive-${sha.orEmpty().take(12)}"),
            "description" to kotlinx.serialization.json.JsonPrimitive("Run the lab's compilation analyser over the captures in the pinned archive " +
                archive.substringAfterLast('/')),
        )
        else -> return body
    }
    return JsonObject(defaults.filterKeys { it !in body } + body)
}
