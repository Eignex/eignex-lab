package com.eignex.lab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** A commit the lab refuses to build: its code came from no trusted user. Not retried. */
class UntrustedCommit(message: String) : IllegalArgumentException(message)

/**
 * Which commits the lab builds and runs: a commit on `main`, or one on a branch every push to which a trusted GitHub
 * user made ([Config.trustedUsers]). Who pushed comes from GitHub's repository activity, which records the actor of
 * each branch creation, push, force push and merge; a commit's author and committer names are whatever its maker
 * typed, so they prove nothing. A commit is checked when a job is planned, after its ref is resolved, so a branch
 * moved since the job was queued is checked as it is then; a submitted branch is also checked at once
 * ([requireBranch]). Anything unknown fails closed: a branch with no recorded activity, or GitHub not answering.
 */
class Trust(
    private val trusted: Set<String>,
    private val repoUrl: String,
    /** Runs git in the lab's mirror and returns its exit and output. */
    private val git: (List<String>) -> Pair<Int, String>,
    /** GETs a GitHub API path (`/repos/...`) and returns its body; throws when GitHub does not answer. */
    private val github: (String) -> String,
) {
    private val verified: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The repository's `owner/name`, from its GitHub URL; null for another repository, where only `main` runs. */
    private val slug: String? = Regex("github\\.com[/:]([^/]+/[^/]+?)(\\.git)?/?$").find(repoUrl)?.groupValues?.get(1)

    /** Fails with [UntrustedCommit] unless [sha] is on `main` or on a branch only trusted users pushed to. */
    fun requireCommit(sha: String, ref: String) {
        if (sha in verified) return
        if (git(listOf("merge-base", "--is-ancestor", sha, MAIN)).first == 0) {
            verified += sha
            return
        }
        val branches = git(listOf("for-each-ref", "--contains", sha, "--format=%(refname:short)", "refs/heads")).second
            .lines().map { it.trim() }.filter { it.isNotEmpty() && it != MAIN }
        val reasons = branches.map { branch -> branch to untrustedPushers(branch) }
        if (reasons.any { it.second.isEmpty() }) {
            verified += sha
            return
        }
        throw UntrustedCommit(
            "refusing to build $ref (${sha.take(SHORT)}): it is not on $MAIN" +
                (if (branches.isEmpty()) " nor on any branch of ${slug ?: repoUrl}" else ", and every branch holding it had a push by someone other than " +
                    "${trusted.sorted().joinToString()}: " + reasons.joinToString("; ") { (b, who) -> "$b by ${who.joinToString()}" }),
        )
    }

    /** Fails with [UntrustedCommit] when [branch], other than `main`, had a push by anyone but a trusted user. */
    fun requireBranch(branch: String) {
        if (branch == MAIN) return
        val who = untrustedPushers(branch)
        if (who.isNotEmpty()) {
            throw UntrustedCommit("refusing $branch: pushed to by ${who.joinToString()}, not only by ${trusted.sorted().joinToString()}")
        }
    }

    private val pushers = ConcurrentHashMap<String, Pair<Long, List<String>>>()

    /** Who besides the trusted users pushed to [branch], read at most every [BRANCH_TTL_MS]; a branch with no recorded
     *  activity counts as pushed by nobody known. */
    private fun untrustedPushers(branch: String): List<String> {
        pushers[branch]?.takeIf { System.currentTimeMillis() - it.first < BRANCH_TTL_MS }?.let { return it.second }
        return readPushers(branch).also { pushers[branch] = System.currentTimeMillis() to it }
    }

    private fun readPushers(branch: String): List<String> {
        val actors = ArrayList<String>()
        for (page in 1..MAX_PAGES) {
            val got = activity(branch, page)
            actors += got
            if (got.size < PAGE) break
        }
        if (actors.isEmpty()) return listOf("nobody GitHub recorded")
        return actors.filter { it !in trusted }.distinct()
    }

    private fun activity(branch: String, page: Int): List<String> {
        val slug = slug ?: throw UntrustedCommit("cannot tell who pushed $branch: $repoUrl is not on GitHub, so only $MAIN runs")
        val ref = java.net.URLEncoder.encode("refs/heads/$branch", Charsets.UTF_8)
        val body = github("/repos/$slug/activity?ref=$ref&per_page=$PAGE&page=$page")
        return (Json.parseToJsonElement(body) as JsonArray).map { entry ->
            ((entry as JsonObject)["actor"] as? JsonObject)?.get("login")?.jsonPrimitive?.content ?: "a deleted account"
        }
    }

    companion object {
        private const val MAIN = "main"
        private const val SHORT = 9
        private const val PAGE = 100
        private const val MAX_PAGES = 10
        private const val BRANCH_TTL_MS = 5 * 60_000L
        private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

        /** GitHub's REST API, authenticated by [token] when one is given; an unauthenticated client has 60 calls an hour. */
        fun githubApi(token: String?): (String) -> String = { path ->
            val request = HttpRequest.newBuilder(URI("https://api.github.com$path"))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
                .GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) { "GitHub answered ${response.statusCode()} for $path: ${response.body().take(200)}" }
            response.body()
        }
    }
}
