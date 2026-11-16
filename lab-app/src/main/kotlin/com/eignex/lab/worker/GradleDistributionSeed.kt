package com.eignex.lab.worker

import com.eignex.lab.util.hashFile
import com.eignex.lab.util.hex
import com.eignex.lab.util.LeaseLost
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.time.Duration
import java.util.Properties
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class GradleDistributionSeed(
    private val download: (URI, String, Path, Long, () -> Long) -> Boolean = ::downloadGradleAsset,
) {
    data class Receipt(val bytes: Long, val sha256: String, val downloaded: Boolean)

    fun stage(tree: Path, home: Path, remainingMs: () -> Long): Receipt? {
        val propertiesFile = tree.resolve("gradle/wrapper/gradle-wrapper.properties")
        if (!Files.isRegularFile(propertiesFile, NOFOLLOW_LINKS)) return null
        val properties = Properties().also { Files.newInputStream(propertiesFile).use(it::load) }
        val url = properties.getProperty("distributionUrl") ?: return null
        val match = Regex("https://(?:services|downloads)\\.gradle\\.org/distributions/(gradle-([0-9]+(?:\\.[0-9]+){1,2})-(?:bin|all)\\.zip)")
            .matchEntire(url) ?: return null
        val sha = properties.getProperty("distributionSha256Sum")?.lowercase()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
        if (properties.getProperty("distributionBase", "GRADLE_USER_HOME") != "GRADLE_USER_HOME" ||
            properties.getProperty("zipStoreBase", "GRADLE_USER_HOME") != "GRADLE_USER_HOME" ||
            properties.getProperty("distributionPath", "wrapper/dists") != "wrapper/dists" ||
            properties.getProperty("zipStorePath", "wrapper/dists") != "wrapper/dists") return null
        val name = match.groupValues[1]
        val version = match.groupValues[2]
        val key = java.math.BigInteger(1, MessageDigest.getInstance("MD5").digest(url.toByteArray())).toString(36)
        val directory = home.resolve("wrapper/dists/${name.removeSuffix(".zip")}/$key")
        val zip = directory.resolve(name)
        fun active() { if (remainingMs() <= 0L) throw LeaseLost("Gradle distribution staging deadline expired") }
        active()
        if (Files.isRegularFile(directory.resolve("$name.ok"), NOFOLLOW_LINKS) &&
            Files.isRegularFile(directory.resolve("gradle-$version/bin/gradle"), NOFOLLOW_LINKS)) return null
        if (Files.isRegularFile(zip, NOFOLLOW_LINKS) && hashFile(zip, ::active) == sha) {
            return Receipt(Files.size(zip), sha, false)
        }
        Files.createDirectories(directory)
        val metadata = Files.createTempFile(directory, ".release-", ".json")
        val pending = Files.createTempFile(directory, ".$name-", ".part")
        try {
            val release = URI("https://api.github.com/repos/gradle/gradle-distributions/releases/tags/v$version")
            if (!download(release, "application/vnd.github+json", metadata, 2L * 1024 * 1024, remainingMs)) return null
            active()
            // Malformed metadata falls back to the wrapper path instead of failing the build.
            val (id, bytes, published) = try {
                val assets = Json.parseToJsonElement(Files.readString(metadata)).jsonObject["assets"]?.jsonArray ?: return null
                val asset = assets.map { it.jsonObject }.singleOrNull { it["name"]?.jsonPrimitive?.content == name } ?: return null
                Triple(asset["id"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return null,
                    asset["size"]?.jsonPrimitive?.longOrNull?.takeIf { it in 1..512L * 1024 * 1024 } ?: return null,
                    asset["digest"]?.jsonPrimitive?.contentOrNull ?: return null)
            } catch (_: IllegalArgumentException) { return null }
            require(published == "sha256:$sha") { "Gradle release digest differs from the pinned wrapper checksum" }
            val uri = URI("https://api.github.com/repos/gradle/gradle-distributions/releases/assets/$id")
            if (!download(uri, "application/octet-stream", pending, bytes, remainingMs)) return null
            active()
            require(Files.size(pending) == bytes && hashFile(pending, ::active) == sha) { "Gradle distribution bytes failed verification" }
            Files.move(pending, zip, ATOMIC_MOVE, REPLACE_EXISTING)
            return Receipt(bytes, sha, true)
        } finally {
            Files.deleteIfExists(metadata)
            Files.deleteIfExists(pending)
        }
    }
}

private val gradleAssetClient by lazy {
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build()
}

private fun downloadGradleAsset(uri: URI, accept: String, target: Path, maximum: Long, remainingMs: () -> Long): Boolean {
    val available = remainingMs()
    if (available <= 0L) throw LeaseLost("Gradle distribution staging deadline expired")
    val request = HttpRequest.newBuilder(uri).header("Accept", accept).header("User-Agent", "eignex-lab")
        .timeout(Duration.ofMillis(minOf(available, 120_000L))).GET().build()
    val future = gradleAssetClient.sendAsync(request, HttpResponse.BodyHandlers.ofFile(target))
    try {
        while (true) {
            val remaining = remainingMs()
            if (remaining <= 0L) throw LeaseLost("Gradle distribution staging deadline expired")
            if (Files.size(target) > maximum) return false
            try {
                val response = future.get(minOf(100L, remaining), TimeUnit.MILLISECONDS)
                return response.statusCode() == 200 && Files.size(target) <= maximum
            } catch (_: TimeoutException) {
            } catch (failure: ExecutionException) {
                if (failure.cause is java.io.IOException) return false
                throw failure
            }
        }
    } finally {
        future.cancel(true)
    }
}
