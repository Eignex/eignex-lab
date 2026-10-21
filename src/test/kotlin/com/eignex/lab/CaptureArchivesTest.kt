package com.eignex.lab

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CaptureArchivesTest {
    private val temp: Path = Files.createTempDirectory("archives")
    private val config = Config(dataDir = temp.resolve("lab"), passwordHash = null)
    private val store = Store(config.dataDir.createDirectories().resolve("lab.db"))
    private val sample = checkNotNull(javaClass.getResource("/compilation/tasks.log")).readText()
    private val profile = "evidence/returned-files/cases/0/profile-a"
    private val source = SourceProvenance(6971, 0, "s/p", "control", "main", "5".repeat(40))
    private val release = "https://github.com/Eignex/klause-lab/releases/download/active-evidence-test"

    @AfterTest
    fun cleanUp() {
        temp.toFile().deleteRecursively()
    }

    /**
     * A campaign-evidence archive and manifest, packed by `campaign-evidence pack` into [into], of a returned job whose
     * case 0 left a HotSpot log, its measurement manifest and the report the lab wrote then; its archive and manifest.
     */
    private fun packed(into: Path = config.archiveDir): Pair<Path, Path> {
        val tree = temp.resolve("publication").createDirectories()
        val case = tree.resolve(profile).createDirectories()
        case.resolve(CliMeasurements.COMPILATION_LOG).writeText(sample)
        CliMeasurements.writeManifest(case, 0, 1, "m-1", "lab", "solve-one", 0, "-Xmx1g", null, macOs = false, compilation = "jfr+log")
        CompilationReports.analyzeAndWrite(case, source, WorkerProvenance(analyzer = "klause-lab compilation analyser, report schema 1"))
        tree.resolve("evidence/returned-files/job.log").writeText("job 6971\n")
        val catalog = JsonObject(mapOf(
            "campaign" to JsonPrimitive("compilation-test"),
            "purpose" to JsonPrimitive("Retain a profiled case's captures"),
            "references" to JsonArray(listOf(JsonPrimitive("https://github.com/Eignex/klause-lab/issues/51"))),
            "limitations" to JsonArray(listOf(JsonPrimitive("A test archive."))),
            "roles" to JsonObject(CampaignEvidence.ROLES.associateWith { JsonArray(listOf(JsonPrimitive("$profile/${CompilationReports.REPORT}"))) }),
        ))
        tree.resolve("catalog.json").writeText(catalog.toString())
        val (archive, manifest) = CampaignEvidence.pack(tree, into)
        return archive to manifest
    }

    private fun shaOf(archive: Path) = archive.name.removeSuffix(".tar.gz").substringAfterLast('-')

    /** An archive whose single member, besides its catalog, is [name] of [type], with a manifest that lists it as it is. */
    private fun crafted(name: String, type: Char = TarWriter.REGULAR, link: String = ""): Pair<Path, Path> {
        val dir = config.archiveDir.createDirectories()
        val catalog = JsonObject(mapOf(
            "campaign" to JsonPrimitive("crafted"), "purpose" to JsonPrimitive("p"),
            "references" to JsonArray(listOf(JsonPrimitive("r"))), "limitations" to JsonArray(listOf(JsonPrimitive("l"))),
            "roles" to JsonObject(CampaignEvidence.ROLES.associateWith { JsonArray(listOf(JsonPrimitive("catalog.json"))) }),
        )).toString().toByteArray()
        val content = "jfr".toByteArray()
        val bytes = ByteArrayOutputStream()
        PythonGzip(bytes).use { gz ->
            val tar = TarWriter(gz)
            tar.file("catalog.json", catalog.size.toLong(), ByteArrayInputStream(catalog))
            if (type == TarWriter.REGULAR) tar.file(name, content.size.toLong(), ByteArrayInputStream(content))
            else tar.entry(name, 0, type, link)
            tar.finish()
        }
        val sha = CompilationReports.hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))
        val archive = dir.resolve("crafted-$sha.tar.gz")
        Files.write(archive, bytes.toByteArray())
        fun entry(data: ByteArray) = JsonObject(mapOf("size" to JsonPrimitive(data.size),
            "sha256" to JsonPrimitive(CompilationReports.hex(java.security.MessageDigest.getInstance("SHA-256").digest(data)))))
        val manifest = JsonObject(mapOf(
            "version" to JsonPrimitive(1),
            "catalog" to Json.parseToJsonElement(String(catalog)),
            "files" to JsonObject(mapOf("catalog.json" to entry(catalog), name to entry(if (type == TarWriter.REGULAR) content else ByteArray(0)))),
            "archive" to JsonObject(mapOf("name" to JsonPrimitive(archive.name), "size" to JsonPrimitive(Files.size(archive)), "sha256" to JsonPrimitive(sha))),
        ))
        val manifestPath = dir.resolve("crafted-$sha.manifest.json")
        manifestPath.writeText(CampaignEvidence.pythonJson(manifest) + "\n")
        return archive to manifestPath
    }

    private fun analysisJob(spec: AnalyzeSpec): Job {
        val experiment = ExperimentSpec("analyze-archive", description = "archive", analyze = spec)
        Experiments.validate(experiment, 4)
        store.create(experiment.name, "main", emptyList(), experiment = experiment)
        return checkNotNull(store.next())
    }

    /** GitHub as a test serves it: a release asset URL redirects to the asset host, which serves [assets] by name. */
    private fun github(assets: Map<String, ByteArray>, redirect: String = "https://release-assets.githubusercontent.com/assets/") =
        CaptureArchives.Http { uri ->
            val name = uri.path.substringAfterLast('/')
            when {
                uri.host == "github.com" -> CaptureArchives.Response(302, redirect + name, null, ByteArrayInputStream(ByteArray(0)))
                name in assets -> assets.getValue(name).let { CaptureArchives.Response(200, null, it.size.toLong(), ByteArrayInputStream(it)) }
                else -> CaptureArchives.Response(404, null, null, ByteArrayInputStream(ByteArray(0)))
            }
        }

    @Test
    fun `an analysis job analyses a pinned local archive's captures into its own directory, with no original job`() {
        val (archive, _) = packed()
        val job = analysisJob(AnalyzeSpec(archive = archive.name, sha256 = shaOf(archive)))

        Runner(config, store).analyze(job)

        assertEquals(Status.DONE, store.job(job.id)!!.status, store.job(job.id)!!.error)
        val dir = config.jobDir(job.id)
        val result = Json.decodeFromString(AnalysisResult.serializer(), Files.readString(dir.resolve(CompilationReports.ANALYSIS)))
        assertNull(result.job)
        val provenance = checkNotNull(result.archive)
        assertEquals(archive.name to shaOf(archive), provenance.url to provenance.sha256)
        assertEquals("compilation-test", provenance.campaign)
        assertEquals(CompilationReports.sha256(dir.resolve(CaptureArchives.MANIFEST)), provenance.manifestSha256)
        val entry = result.reports.single()
        assertEquals("${CaptureArchives.ROOT}/$profile/${CompilationReports.REPORT}", entry.report)
        assertEquals("${CaptureArchives.ROOT}/$profile/${CompilationReports.TASKS}", entry.tasks)
        assertEquals(8, entry.compilations)
        val report = checkNotNull(CompilationReports.read(dir.resolve(entry.report)))
        assertEquals(provenance.copy(member = profile), report.provenance.archive)
        assertEquals(job.id, report.provenance.worker.analyzeJob)
        // The case's job, arm and commit come from the report the archive held beside the same captures.
        assertEquals(source, report.provenance.source?.copy(from = null))
        assertContains(report.provenance.source?.from.orEmpty(), "archived")
        assertFalse(report.missing.any { it.what == "source" }, report.missing.toString())
        assertEquals(9, CompilationReports.readTasks(dir.resolve(checkNotNull(entry.tasks))).size)
        assertFalse(dir.resolve("download").exists())
        assertTrue(archive.exists(), "a lab-local archive stays where it was")
        val page = jobPage(config, store.job(job.id)!!, failedOnly = false)
        assertContains(page, "pinned archive <code>${archive.name}</code>")
        assertContains(page, "/jobs/${job.id}/files/${entry.report}")
        assertTrue(JobTrim.keeps(Path.of(entry.report)) && JobTrim.keeps(Path.of(checkNotNull(entry.tasks))))
    }

    @Test
    fun `a release asset is downloaded through GitHub's redirect into the analysis job's directory`() {
        val (archive, manifest) = packed(temp.resolve("assets"))
        val url = "$release/${archive.name}"
        val job = analysisJob(AnalyzeSpec(archive = url, sha256 = shaOf(archive), manifestSha256 = CompilationReports.sha256(manifest)))

        Runner(config, store, archiveHttp = github(mapOf(archive.name to archive.readBytes(), manifest.name to manifest.readBytes()))).analyze(job)

        assertEquals(Status.DONE, store.job(job.id)!!.status, store.job(job.id)!!.error)
        val dir = config.jobDir(job.id)
        val result = Json.decodeFromString(AnalysisResult.serializer(), Files.readString(dir.resolve(CompilationReports.ANALYSIS)))
        assertEquals(url, result.archive?.url)
        assertEquals("$release/${manifest.name}", result.archive?.manifest)
        assertFalse(dir.resolve("download").exists(), "the download goes once extracted")
        assertTrue(dir.resolve(CaptureArchives.ROOT).resolve(profile).resolve(CliMeasurements.COMPILATION_LOG).exists())
    }

    @Test
    fun `an archive whose bytes differ from its pin fails the job, saying so`() {
        val (archive, _) = packed()
        val job = analysisJob(AnalyzeSpec(archive = archive.name, sha256 = "0".repeat(64)))

        Runner(config, store).analyze(job)

        val ended = store.job(job.id)!!
        assertEquals(Status.FAILED, ended.status)
        assertContains(ended.error.orEmpty(), "not the pinned ${"0".repeat(64)}")
        assertFalse(config.jobDir(job.id).resolve(CaptureArchives.ROOT).exists(), "nothing is extracted")
    }

    @Test
    fun `a manifest pin that differs, or a manifest of another archive, is refused`() {
        val (archive, manifest) = packed()
        val pinned = AnalyzeSpec(archive = archive.name, sha256 = shaOf(archive), manifestSha256 = "1".repeat(64))
        assertContains(assertFailsWith<ArchiveException> { CaptureArchives.fetch(pinned, config, temp.resolve("job1")) }.message.orEmpty(),
            "manifest's SHA-256")
        val (other, _) = crafted("cases/0/profile-a/cli.jfr")
        Files.copy(manifest, config.archiveDir.resolve("mixed.manifest.json"))
        val mixed = AnalyzeSpec(archive = other.name, sha256 = shaOf(other), manifest = "mixed.manifest.json")
        assertContains(assertFailsWith<ArchiveException> { CaptureArchives.fetch(mixed, config, temp.resolve("job2")) }.message.orEmpty(),
            "manifest is of archive")
    }

    @Test
    fun `an archive with a traversal path or a symbolic link is refused before anything is extracted`() {
        for ((name, type) in listOf("cases/0/profile-a/../../../escape/cli.jfr" to TarWriter.REGULAR, "../escape" to TarWriter.REGULAR,
            "cases/0/profile-a/cli.jfr" to TarWriter.SYMLINK)) {
            val (archive, manifest) = crafted(name, type, link = "/etc/passwd")
            val dir = temp.resolve("job-${archive.name.hashCode()}")
            val error = runCatching { CaptureArchives.fetch(AnalyzeSpec(archive = archive.name, sha256 = shaOf(archive)), config, dir) }.exceptionOrNull()
            assertNotNull(error, name)
            assertTrue(error.message.orEmpty().let { "unsafe path" in it || "nonregular" in it }, "$name: ${error.message}")
            assertFalse(dir.resolve(CaptureArchives.ROOT).exists(), name)
            Files.delete(archive)
            Files.delete(manifest)
        }
        // Extraction checks every name itself too, verified or not.
        val (archive, _) = crafted("cases/0/profile-a/../../../escape/cli.jfr")
        assertContains(assertFailsWith<EvidenceException> { CaptureArchives.extract(archive, temp.resolve("raw/archive"), Long.MAX_VALUE) }.message.orEmpty(),
            "unsafe path")
    }

    @Test
    fun `an archive over the size limits is refused, by its length, as it streams, and by what it unpacks to`() {
        val (archive, manifest) = packed(temp.resolve("assets"))
        val spec = AnalyzeSpec(archive = archive.name, sha256 = shaOf(archive))
        Files.createDirectories(config.archiveDir)
        Files.copy(archive, config.archiveDir.resolve(archive.name))
        Files.copy(manifest, config.archiveDir.resolve(manifest.name))
        val small = config.copy(archiveMaxBytes = 100)
        assertContains(assertFailsWith<ArchiveException> { CaptureArchives.fetch(spec, small, temp.resolve("j1")) }.message.orEmpty(), "over the lab's")
        val unpacked = config.copy(archiveMaxUnpackedBytes = 100)
        assertContains(assertFailsWith<ArchiveException> { CaptureArchives.fetch(spec, unpacked, temp.resolve("j2")) }.message.orEmpty(), "unpacks to")
        // A download says its length, or does not and runs over as it streams.
        val target = temp.resolve("download.tar.gz")
        val big = ByteArray(1000)
        val said = CaptureArchives.Http { CaptureArchives.Response(200, null, 1000, ByteArrayInputStream(big)) }
        assertContains(assertFailsWith<ArchiveException> { CaptureArchives.download(URI("$release/x.tar.gz"), target, 100, config, said) }.message.orEmpty(),
            "1000 bytes, over")
        val unsaid = CaptureArchives.Http { CaptureArchives.Response(200, null, null, ByteArrayInputStream(big)) }
        assertContains(assertFailsWith<ArchiveException> { CaptureArchives.download(URI("$release/x.tar.gz"), target, 100, config, unsaid) }.message.orEmpty(),
            "runs over")
        assertFalse(target.exists(), "a download cut off is removed")
    }

    @Test
    fun `only HTTPS release assets under an allowed prefix are fetched, and redirects only to GitHub's asset hosts`() {
        val sha = "a".repeat(64)
        fun refusal(url: String) = CaptureArchives.refusal(AnalyzeSpec(archive = url, sha256 = sha), config)
        assertNull(refusal("$release/a.tar.gz"))
        assertContains(refusal("http://github.com/Eignex/klause-lab/releases/download/t/a.tar.gz")!!, "must be https")
        assertContains(refusal("https://evil.example/Eignex/a.tar.gz")!!, "only from https://github.com/Eignex/")
        assertContains(refusal("https://github.com/someone/klause-lab/releases/download/t/a.tar.gz")!!, "only from")
        assertContains(refusal("https://github.com/Eignex/klause-lab/archive/main.tar.gz")!!, "release asset")
        assertContains(refusal("$release/a.tar.gz?x=1")!!, "no credentials, query")
        assertContains(refusal("https://user@github.com/Eignex/klause-lab/releases/download/t/a.tar.gz")!!, "no credentials")
        assertContains(refusal("https://github.com/Eignex/klause-lab/releases/download/t/../../x/a.tar.gz")!!, "plain path")
        assertContains(refusal("$release/a.zip")!!, "must end in .tar.gz")
        assertContains(refusal("../a.tar.gz")!!, "unsafe path")
        assertContains(refusal("missing.tar.gz")!!, "no missing.tar.gz in the lab's archive directory")
        assertContains(CaptureArchives.refusal(AnalyzeSpec(archive = "$release/a.tar.gz"), config)!!, "must pin")
        val other = config.copy(archivePrefixes = listOf("https://mirror.example/evidence/"))
        assertNull(CaptureArchives.refusal(AnalyzeSpec(archive = "https://mirror.example/evidence/a.tar.gz", sha256 = sha), other))
        // GitHub's redirect to anywhere but its asset hosts is not followed.
        val evil = github(emptyMap(), redirect = "https://evil.example/")
        assertContains(assertFailsWith<ArchiveException> {
            CaptureArchives.download(URI("$release/a.tar.gz"), temp.resolve("a.tar.gz"), 100, config, evil)
        }.message.orEmpty(), "not allowed")
        val plain = github(emptyMap(), redirect = "http://release-assets.githubusercontent.com/")
        assertContains(assertFailsWith<ArchiveException> {
            CaptureArchives.download(URI("$release/a.tar.gz"), temp.resolve("a.tar.gz"), 100, config, plain)
        }.message.orEmpty(), "not allowed")
    }

    @Test
    fun `an analysis spec names a job or a pinned archive, and the API refuses an archive it may not fetch`() {
        fun valid(analyze: AnalyzeSpec) = runCatching { Experiments.validate(ExperimentSpec("a", description = "d", analyze = analyze), 4) }
        assertTrue(valid(AnalyzeSpec(archive = "$release/a.tar.gz", sha256 = "a".repeat(64))).isSuccess)
        assertContains(valid(AnalyzeSpec(archive = "$release/a.tar.gz")).exceptionOrNull()?.message.orEmpty(), "must pin")
        assertContains(valid(AnalyzeSpec(archive = "$release/a.tar.gz", sha256 = "A".repeat(64))).exceptionOrNull()?.message.orEmpty(), "must pin")
        assertContains(valid(AnalyzeSpec(7, archive = "$release/a.tar.gz", sha256 = "a".repeat(64))).exceptionOrNull()?.message.orEmpty(), "one of them")
        assertContains(valid(AnalyzeSpec()).exceptionOrNull()?.message.orEmpty(), "one of them")
        assertContains(valid(AnalyzeSpec(7, sha256 = "a".repeat(64))).exceptionOrNull()?.message.orEmpty(), "pin an archive")
        val (archive, _) = packed()
        testApplication {
            application { api(config, store, HostReport("Test/Arm64", 4, "java", "engine", true, emptyList())) }
            suspend fun analyze(body: String) = client.post("/experiments") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            val refused = analyze("""{"analyze": {"archive": "https://evil.example/a.tar.gz", "sha256": "${"a".repeat(64)}"}}""")
            val queued = analyze("""{"analyze": {"archive": "${archive.name}", "sha256": "${shaOf(archive)}"}}""")

            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertContains(refused.bodyAsText(), "cannot analyse archive https://evil.example/a.tar.gz: archives are fetched only from")
            assertEquals(HttpStatusCode.Created, queued.status, queued.bodyAsText())
            val job = store.jobs(10, null, null, withCommands = false).single()
            assertEquals("analyze-archive-${shaOf(archive).take(12)}", job.name)
            assertEquals(AnalyzeSpec(archive = archive.name, sha256 = shaOf(archive)), job.experiment?.analyze)
        }
    }

    @Test
    fun `a schema 1 analysis of a job still shows on its page, its reports in that job's files`() {
        val spec = ExperimentSpec("analyze-6971", description = "again", analyze = AnalyzeSpec(6971))
        val id = store.create(spec.name, "main", emptyList(), experiment = spec)
        store.next()
        store.finish(id, Status.DONE)
        val v1 = """{"job":6971,"kind":"compilation","reports":[{"case":0,"report":"cases/0/profile-a/compilation-report.json","compilations":3,""" +
            """"compilerMs":12.5,"complete":true,"markers":[]}]}"""
        config.jobDir(id).createDirectories().resolve(CompilationReports.ANALYSIS).writeText(v1)

        val page = jobPage(config, store.job(id)!!, failedOnly = false)

        assertContains(page, "retained captures of job <a href=\"/jobs/6971\">6971</a>")
        assertContains(page, "<a href=\"/jobs/6971/files/cases/0/profile-a/compilation-report.json\">")
        assertFalse("tasks</a>" in page)
    }
}
