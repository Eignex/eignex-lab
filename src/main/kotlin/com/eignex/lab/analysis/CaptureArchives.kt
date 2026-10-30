package com.eignex.lab.analysis

import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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
 * path under the lab's archive directory as [url] (an older report may hold the URL it was downloaded from), its
 * [sha256] as pinned and checked and its size, and the manifest it was verified against, by its path, its own SHA-256
 * and the campaign its catalog names, with how many files verification matched. [member] is the profile directory
 * within the archive a report's captures were in.
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
 * manifest ([CampaignEvidence]), pinned by the archive's SHA-256. An archive is a regular file under the lab's archive
 * directory ([Config.archiveDir]), put there by whoever runs the lab; nothing is downloaded. Its size is capped
 * ([Config.archiveMaxBytes]), it is checked against the pin, and verified member by member against the manifest before
 * anything is extracted; then only the profile directories' captures are, into the analysis job's own directory, every
 * path checked to stay inside it.
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
    private val SHA = Regex("[0-9a-f]{64}")
    /** A capture of a profile directory: `…/cases/<i>/profile-<id>/<file>`. */
    private val PROFILE = Regex("(?:.*/)?cases/(\\d+)/(profile-[^/]+)/([^/]+)")
    private val CAPTURES = setOf(CliMeasurements.JFR, CliMeasurements.COMPILATION_LOG, CliMeasurements.COMPILATION_LOG_GZ,
        CliMeasurements.MANIFEST, CliMeasurements.RESOURCES, "solve-record.json")

    /** What [fetch] made ready: the verified archive and manifest, where its profile directories were extracted, and its provenance. */
    class Fetched(val archive: Path, val manifest: Path, val profiles: List<Path>, val provenance: ArchiveProvenance)

    /** A file under the lab's archive directory, at [path], named [shown] relative to that directory. */
    private class Local(val path: Path, val shown: String)

    /**
     * Why [spec]'s archive cannot be analysed on this lab, or null when it can: it needs its SHA-256, a file under the
     * lab's archive directory, and a manifest beside it or named.
     */
    fun refusal(spec: AnalyzeSpec, paths: DataPaths): String? = try {
        locate(spec, paths)
        null
    } catch (e: ArchiveException) {
        e.message
    }

    private fun locate(spec: AnalyzeSpec, paths: DataPaths): Pair<Local, Local> {
        val archive = spec.archive ?: throw ArchiveException("analyze.archive names no archive")
        if (spec.sha256 == null || !SHA.matches(spec.sha256)) {
            throw ArchiveException("analyze.sha256 must pin the archive: its SHA-256 as 64 lowercase hex digits")
        }
        spec.manifestSha256?.let { if (!SHA.matches(it)) throw ArchiveException("analyze.manifestSha256 must be 64 lowercase hex digits") }
        val source = source(archive, SUFFIX, paths)
        val manifest = spec.manifest?.let { source(it, MANIFEST_SUFFIX, paths) }
            ?: Local(source.path.resolveSibling(source.path.name.removeSuffix(SUFFIX) + MANIFEST_SUFFIX), source.shown.removeSuffix(SUFFIX) + MANIFEST_SUFFIX)
        if (!manifest.path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            throw ArchiveException("no manifest ${manifest.shown} beside the archive in the lab's archive directory")
        }
        return source to manifest
    }

    /** [text] as a file under the lab's archive directory, its name ending in [suffix]. */
    private fun source(text: String, suffix: String, paths: DataPaths): Local {
        if ("://" in text) {
            throw ArchiveException("archives are not downloaded: name a file under the lab's archive directory (\$LAB_DATA/archives), not a URL: $text")
        }
        val name = try { CampaignEvidence.safeName(text) } catch (e: EvidenceException) { throw ArchiveException("${e.message}: a lab-local archive is a path under the archive directory") }
        if (!name.endsWith(suffix)) throw ArchiveException("'$name' must end in $suffix")
        val root = paths.archives
        val path = root.resolve(name).normalize()
        if (!path.startsWith(root) || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw ArchiveException("no $name in the lab's archive directory (\$LAB_DATA/${paths.dataDir.relativize(root)})")
        }
        if (!path.isRegularFile(LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().startsWith(root.toRealPath())) {
            throw ArchiveException("$name is not a regular file inside the lab's archive directory")
        }
        return Local(path, name)
    }

    /**
     * Check [spec]'s archive, a file under the lab's archive directory, against its pin, verify every member against the
     * manifest ([CampaignEvidence.verify]) and extract the profile directories' captures under [ROOT] in [dir], the
     * analysis job's directory. Throws [ArchiveException] (or [EvidenceException]) saying why it refused. The archive
     * stays where it is; the manifest is copied in as [MANIFEST].
     */
    fun fetch(spec: AnalyzeSpec, paths: DataPaths, limits: ArchiveLimits, dir: Path): Fetched {
        val (source, manifestSource) = locate(spec, paths)
        val pinned = checkNotNull(spec.sha256)
        val archive = source.path
        val bytes = Files.size(archive)
        if (bytes > limits.maxBytes) throw ArchiveException("the archive is $bytes bytes, over the lab's ${limits.maxBytes} (LAB_ARCHIVE_MAX_MB)")
        val sha = CompilationReports.sha256(archive)
        if (sha != pinned) throw ArchiveException("the archive's SHA-256 is $sha, not the pinned $pinned")
        val manifest = manifestSource.path
        if (Files.size(manifest) > MAX_MANIFEST_BYTES) throw ArchiveException("the manifest is over $MAX_MANIFEST_BYTES bytes")
        val manifestSha = CompilationReports.sha256(manifest)
        spec.manifestSha256?.let { if (it != manifestSha) throw ArchiveException("the manifest's SHA-256 is $manifestSha, not the pinned $it") }
        val document = runCatching { Json.parseToJsonElement(Files.readString(manifest)) as JsonObject }
            .getOrElse { throw ArchiveException("the manifest is not a JSON object") }
        val named = ((document["archive"] as? JsonObject)?.get("sha256") as? JsonPrimitive)?.content
        if (named != pinned) throw ArchiveException("the manifest is of archive $named, not the pinned $pinned")
        val unpacked = (document["files"] as? JsonObject)?.values?.sumOf { ((it as? JsonObject)?.get("size") as? JsonPrimitive)?.longOrNull ?: 0L } ?: 0L
        if (unpacked > limits.maxUnpackedBytes) {
            throw ArchiveException("the archive unpacks to $unpacked bytes, over the lab's ${limits.maxUnpackedBytes} (LAB_ARCHIVE_MAX_UNPACKED_MB)")
        }
        // Every member, its bytes and its name checked against the manifest, before a byte is extracted.
        val verified = CampaignEvidence.verify(archive, manifest)
        val root = dir.resolve(ROOT)
        val profiles = extract(archive, root, limits.maxUnpackedBytes)
        Files.copy(manifest, dir.resolve(MANIFEST), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val campaign = ((document["catalog"] as? JsonObject)?.get("campaign") as? JsonPrimitive)?.content
        val provenance = ArchiveProvenance(source.shown, sha, bytes, archive.name, manifestSource.shown, manifestSha, campaign, verified)
        return Fetched(archive, dir.resolve(MANIFEST), profiles, provenance)
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
