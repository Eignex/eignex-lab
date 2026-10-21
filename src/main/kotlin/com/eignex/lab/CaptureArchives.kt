package com.eignex.lab

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.GZIPInputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Where an analysis job's captures came from when they came from a pinned archive instead of a lab job: the archive's
 * [url] (or its name under the lab's archive directory), its [sha256] as pinned and checked and its size, and the
 * manifest it was verified against, by where it was read, its own SHA-256 and the campaign its catalog names, with how
 * many files verification matched. [member] is the profile directory within the archive a report's captures were in.
 */
@Serializable
data class ArchiveProvenance(
    val url: String,
    val sha256: String,
    val bytes: Long,
    val name: String,
    val manifest: String,
    val manifestSha256: String,
    val campaign: String? = null,
    val verifiedFiles: Int,
    val member: String? = null,
)

/**
 * Saved-capture archives an analysis job analyses ([AnalyzeSpec.archive]): a `campaign-evidence` archive and its
 * manifest ([CampaignEvidence]), pinned by the archive's SHA-256. The lab is reachable from the internet, so an archive
 * must come from an allowed HTTPS prefix ([Config.archivePrefixes], GitHub release assets of Eignex by default), or be
 * a file under [Config.archiveDir]. Its download is capped ([Config.archiveMaxBytes]), checked against the pin, and
 * verified member by member against the manifest before anything is extracted; then only the profile directories'
 * captures are, into the analysis job's own directory, every path checked to stay inside it.
 */
object CaptureArchives {
    /** Where an analysis job extracts an archive's captures, within its own directory, at their paths in the archive. */
    const val ROOT = "archive"
    /** The verified manifest, kept in the analysis job's directory. */
    const val MANIFEST = "archive.manifest.json"
    /** The report an archive held beside a case's captures, extracted under this name and read for the case's source. */
    const val ARCHIVED_REPORT = "archived-compilation-report.json"
    private const val SUFFIX = ".tar.gz"
    private const val MANIFEST_SUFFIX = ".manifest.json"
    private const val MAX_MANIFEST_BYTES = 64L * 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private val SHA = Regex("[0-9a-f]{64}")
    private val GITHUB_ASSET = Regex("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/download/[^/]+/[^/]+")
    /** A capture of a profile directory: `…/cases/<i>/profile-<id>/<file>`. */
    private val PROFILE = Regex("(?:.*/)?cases/(\\d+)/(profile-[^/]+)/([^/]+)")
    private val CAPTURES = setOf(CliMeasurements.JFR, CliMeasurements.COMPILATION_LOG, CliMeasurements.COMPILATION_LOG_GZ,
        CliMeasurements.MANIFEST, CliMeasurements.RESOURCES, "solve-record.json")

    /** One HTTP GET, its redirects not followed: [CaptureArchives] follows them itself, checking each. */
    fun interface Http {
        fun get(uri: URI): Response

        companion object {
            val DEFAULT = Http { uri ->
                val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(30)).build()
                val response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream())
                Response(response.statusCode(), response.headers().firstValue("location").orElse(null),
                    response.headers().firstValueAsLong("content-length").let { if (it.isPresent) it.asLong else null }, response.body())
            }
        }
    }

    class Response(val status: Int, val location: String?, val length: Long?, val body: InputStream) : Closeable {
        override fun close() = body.close()
    }

    /** What [fetch] made ready: the verified archive and manifest, where its profile directories were extracted, and its provenance. */
    class Fetched(val archive: Path, val manifest: Path, val profiles: List<Path>, val provenance: ArchiveProvenance)

    private sealed interface Source {
        val shown: String
    }

    private class Remote(val uri: URI) : Source {
        override val shown: String get() = uri.toString()
    }

    private class Local(val path: Path, override val shown: String) : Source

    /**
     * Why [spec]'s archive cannot be analysed on this lab, or null when it can be fetched: it needs its SHA-256, an
     * allowed HTTPS URL or a file under the lab's archive directory, and a manifest beside it or named.
     */
    fun refusal(spec: AnalyzeSpec, config: Config): String? = try {
        locate(spec, config)
        null
    } catch (e: ArchiveException) {
        e.message
    }

    private fun locate(spec: AnalyzeSpec, config: Config): Pair<Source, Source> {
        val archive = spec.archive ?: throw ArchiveException("analyze.archive names no archive")
        if (spec.sha256 == null || !SHA.matches(spec.sha256)) {
            throw ArchiveException("analyze.sha256 must pin the archive: its SHA-256 as 64 lowercase hex digits")
        }
        spec.manifestSha256?.let { if (!SHA.matches(it)) throw ArchiveException("analyze.manifestSha256 must be 64 lowercase hex digits") }
        val source = source(archive, SUFFIX, config)
        val manifest = spec.manifest?.let { source(it, MANIFEST_SUFFIX, config) } ?: when (source) {
            is Remote -> Remote(URI(source.uri.toString().removeSuffix(SUFFIX) + MANIFEST_SUFFIX))
            is Local -> Local(source.path.resolveSibling(source.path.name.removeSuffix(SUFFIX) + MANIFEST_SUFFIX),
                source.shown.removeSuffix(SUFFIX) + MANIFEST_SUFFIX)
        }
        if (manifest is Local && !manifest.path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            throw ArchiveException("no manifest ${manifest.shown} beside the archive in the lab's archive directory")
        }
        return source to manifest
    }

    /** [text] as an allowed URL, or a file under the lab's archive directory, its name ending in [suffix]. */
    private fun source(text: String, suffix: String, config: Config): Source {
        if ("://" in text) return Remote(allowed(text, suffix, config))
        val name = try { CampaignEvidence.safeName(text) } catch (e: EvidenceException) { throw ArchiveException("${e.message}: a lab-local archive is a path under the archive directory") }
        if (!name.endsWith(suffix)) throw ArchiveException("'$name' must end in $suffix")
        val root = config.archiveDir
        val path = root.resolve(name).normalize()
        if (!path.startsWith(root) || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw ArchiveException("no $name in the lab's archive directory (\$LAB_DATA/${config.dataDir.relativize(root)})")
        }
        if (!path.isRegularFile(LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().startsWith(root.toRealPath())) {
            throw ArchiveException("$name is not a regular file inside the lab's archive directory")
        }
        return Local(path, name)
    }

    /** [text] as a URI when it is an HTTPS URL under one of [Config.archivePrefixes], plain, and names a file ending in [suffix]. */
    internal fun allowed(text: String, suffix: String, config: Config): URI {
        val uri = try { URI(text) } catch (e: java.net.URISyntaxException) { throw ArchiveException("not a URL: $text") }
        if (uri.scheme != "https") throw ArchiveException("an archive URL must be https: $text")
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || (uri.port != -1 && uri.port != 443)) {
            throw ArchiveException("an archive URL takes no credentials, query, fragment or port: $text")
        }
        val path = uri.rawPath.orEmpty()
        if (path.split('/').any { it == "." || it == ".." || it.contains("%2f", ignoreCase = true) } || '\\' in path) {
            throw ArchiveException("an archive URL must be a plain path: $text")
        }
        if (config.archivePrefixes.none { text.startsWith(it) }) {
            throw ArchiveException("archives are fetched only from ${config.archivePrefixes.joinToString(" or ")} (LAB_ARCHIVE_PREFIXES): $text")
        }
        if (uri.host == "github.com" && !GITHUB_ASSET.matches(path)) {
            throw ArchiveException("a GitHub archive must be a release asset, https://github.com/<owner>/<repo>/releases/download/<tag>/<asset>: $text")
        }
        if (!path.endsWith(suffix)) throw ArchiveException("'${path.substringAfterLast('/')}' must end in $suffix")
        return uri
    }

    /**
     * Fetch [spec]'s archive and manifest into [dir], the analysis job's directory, check the archive against its pin,
     * verify every member against the manifest ([CampaignEvidence.verify]) and extract the profile directories'
     * captures under [ROOT]. Throws [ArchiveException] (or [EvidenceException]) saying why it refused. A download is
     * removed once extracted; the manifest stays as [MANIFEST].
     */
    fun fetch(spec: AnalyzeSpec, config: Config, dir: Path, http: Http = Http.DEFAULT): Fetched {
        val (source, manifestSource) = locate(spec, config)
        val downloads = dir.resolve("download")
        Files.createDirectories(downloads)
        try {
            return fetch(spec, config, dir, http, source, manifestSource, downloads)
        } finally {
            deleteTree(downloads)
        }
    }

    private fun fetch(spec: AnalyzeSpec, config: Config, dir: Path, http: Http, source: Source, manifestSource: Source, downloads: Path): Fetched {
        val pinned = checkNotNull(spec.sha256)
        val archive = when (source) {
            is Local -> source.path
            is Remote -> downloads.resolve(fileName(source.uri)).also { download(source.uri, it, config.archiveMaxBytes, config, http) }
        }
        val bytes = Files.size(archive)
        if (bytes > config.archiveMaxBytes) throw ArchiveException("the archive is $bytes bytes, over the lab's ${config.archiveMaxBytes} (LAB_ARCHIVE_MAX_MB)")
        val sha = CompilationReports.sha256(archive)
        if (sha != pinned) throw ArchiveException("the archive's SHA-256 is $sha, not the pinned $pinned")
        val manifest = when (manifestSource) {
            is Local -> manifestSource.path
            is Remote -> downloads.resolve(fileName(manifestSource.uri)).also { download(manifestSource.uri, it, MAX_MANIFEST_BYTES, config, http) }
        }
        if (Files.size(manifest) > MAX_MANIFEST_BYTES) throw ArchiveException("the manifest is over $MAX_MANIFEST_BYTES bytes")
        val manifestSha = CompilationReports.sha256(manifest)
        spec.manifestSha256?.let { if (it != manifestSha) throw ArchiveException("the manifest's SHA-256 is $manifestSha, not the pinned $it") }
        val document = runCatching { Json.parseToJsonElement(Files.readString(manifest)) as JsonObject }
            .getOrElse { throw ArchiveException("the manifest is not a JSON object") }
        val named = ((document["archive"] as? JsonObject)?.get("sha256") as? JsonPrimitive)?.content
        if (named != pinned) throw ArchiveException("the manifest is of archive $named, not the pinned $pinned")
        val unpacked = (document["files"] as? JsonObject)?.values?.sumOf { ((it as? JsonObject)?.get("size") as? JsonPrimitive)?.longOrNull ?: 0L } ?: 0L
        if (unpacked > config.archiveMaxUnpackedBytes) {
            throw ArchiveException("the archive unpacks to $unpacked bytes, over the lab's ${config.archiveMaxUnpackedBytes} (LAB_ARCHIVE_MAX_UNPACKED_MB)")
        }
        // Every member, its bytes and its name checked against the manifest, before a byte is extracted.
        val verified = CampaignEvidence.verify(archive, manifest)
        val root = dir.resolve(ROOT)
        val profiles = extract(archive, root, config.archiveMaxUnpackedBytes)
        Files.copy(manifest, dir.resolve(MANIFEST), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val campaign = ((document["catalog"] as? JsonObject)?.get("campaign") as? JsonPrimitive)?.content
        val provenance = ArchiveProvenance(source.shown, sha, bytes, archive.name, manifestSource.shown, manifestSha, campaign, verified)
        return Fetched(archive, dir.resolve(MANIFEST), profiles, provenance)
    }

    /** The last segment of [uri]'s path, which must be a safe file name. */
    private fun fileName(uri: URI): String {
        val name = java.net.URLDecoder.decode(uri.rawPath.substringAfterLast('/'), Charsets.UTF_8)
        if (name.isEmpty() || '/' in name || '\\' in name || name == "." || name == "..") throw ArchiveException("unsafe file name in $uri")
        return name
    }

    /**
     * Download [uri] to [target], at most [max] bytes, following at most [MAX_REDIRECTS] redirects, each to HTTPS on
     * the URL's own host or one of [Config.archiveRedirectHosts].
     */
    internal fun download(uri: URI, target: Path, max: Long, config: Config, http: Http) {
        var current = uri
        repeat(MAX_REDIRECTS + 1) {
            http.get(current).use { response ->
                when (response.status) {
                    200 -> {
                        response.length?.let { if (it > max) throw ArchiveException("$current is $it bytes, over the lab's limit of $max") }
                        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                            val buffer = ByteArray(1 shl 16)
                            var total = 0L
                            while (true) {
                                val read = response.body.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > max) {
                                    out.close()
                                    Files.deleteIfExists(target)
                                    throw ArchiveException("$current runs over the lab's limit of $max bytes")
                                }
                                out.write(buffer, 0, read)
                            }
                        }
                        return
                    }
                    301, 302, 303, 307, 308 -> {
                        val next = current.resolve(response.location ?: throw ArchiveException("$current redirects nowhere"))
                        if (next.scheme != "https" || (next.host != uri.host && next.host !in config.archiveRedirectHosts)) {
                            throw ArchiveException("$uri redirects to ${next.scheme}://${next.host}, which is not allowed (LAB_ARCHIVE_REDIRECT_HOSTS)")
                        }
                        current = next
                    }
                    else -> throw ArchiveException("$current answered HTTP ${response.status}")
                }
            }
        }
        throw ArchiveException("$uri redirects more than $MAX_REDIRECTS times")
    }

    /**
     * Extract [archive]'s profile-directory captures into [root], which must not exist yet, each at its path in the
     * archive: at most [max] bytes, every name a plain relative path ([CampaignEvidence.safeName]), every member a
     * regular file. A case's archived report is extracted as [ARCHIVED_REPORT]. Returns the profile directories.
     */
    internal fun extract(archive: Path, root: Path, max: Long): List<Path> {
        Files.createDirectories(root.parent)
        Files.createDirectory(root)
        val profiles = LinkedHashSet<Path>()
        var total = 0L
        Files.newInputStream(archive).buffered().use { raw ->
            val bundle = TarReader(GZIPInputStream(raw))
            while (true) {
                val member = bundle.next() ?: break
                val name = CampaignEvidence.safeName(member.name)
                if (!member.regular) throw ArchiveException("nonregular member: $name")
                val match = PROFILE.matchEntire(name) ?: continue
                val file = match.groupValues[3]
                val kept = when (file) {
                    in CAPTURES -> file
                    CompilationReports.REPORT -> ARCHIVED_REPORT
                    else -> continue
                }
                val target = root.resolve(name).resolveSibling(kept).normalize()
                if (!target.startsWith(root)) throw ArchiveException("unsafe path: $name")
                total += member.size
                if (total > max) throw ArchiveException("the archive's captures run over the lab's limit of $max bytes")
                Files.createDirectories(target.parent)
                Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                    bundle.content { block, length -> out.write(block, 0, length) }
                }
                profiles.add(target.parent)
            }
        }
        return profiles.filter(CompilationReports::hasCaptures)
            .sortedWith(compareBy({ it.parent.name.toIntOrNull() ?: Int.MAX_VALUE }, { root.relativize(it).toString() }))
    }

    /**
     * The case of [profile], an extracted profile directory: its index from its path, and its job, problem, arm and
     * commit from the report the archive held beside it, when that report's captures are these, byte for byte.
     */
    fun source(profile: Path): SourceProvenance {
        val case = profile.parent.name.toInt()
        val archived = profile.resolve(ARCHIVED_REPORT).takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            ?.let(CompilationReports::read) ?: return SourceProvenance(case = case)
        val source = archived.provenance.source ?: return SourceProvenance(case = case)
        val same = archived.provenance.captures.isNotEmpty() && archived.provenance.captures.all { capture ->
            val file = profile.resolve(capture.name)
            Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) == capture.bytes && CompilationReports.sha256(file) == capture.sha256
        }
        if (!same || source.case != case) return SourceProvenance(case = case)
        return source.copy(from = "the archived ${CompilationReports.REPORT} (schema ${archived.schemaVersion}) beside the same captures")
    }
}

internal class ArchiveException(message: String) : IOException(message)
