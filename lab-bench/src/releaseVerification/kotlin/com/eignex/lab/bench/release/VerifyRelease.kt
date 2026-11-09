package com.eignex.lab.bench.release

import com.sun.net.httpserver.SimpleFileServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Resolve staged GitHub Release assets from a clean, unauthenticated Gradle consumer:
 * `VerifyRelease ASSETS VERSION GRADLE`, as `:lab-bench:verifyRelease` runs it. It checks every staged checksum, the
 * SDK's dependency boundary and Java 17 bytecode, then serves the assets in the release URL layout to an independent
 * consumer build and drives that consumer through the process contract.
 */
public fun main(args: Array<String>) {
    require(args.size == 3) { "usage: VerifyRelease ASSETS VERSION GRADLE" }
    verify(Path.of(args[0]).toAbsolutePath().normalize(), args[1], Path.of(args[2]).toAbsolutePath().normalize())
}

private val CREDENTIAL_WORDS = listOf("TOKEN", "PASSWORD", "SECRET", "CREDENTIAL", "GITHUB", "ORG_GRADLE_PROJECT")
private val JVM_OPTION_VARIABLES = listOf("GRADLE_OPTS", "JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS")

private fun verify(assets: Path, version: String, gradle: Path) {
    val checksums = (assets.resolve("SHA256SUMS")).readText().lines().filter { it.isNotEmpty() }.associate { line ->
        val parts = line.split("  ", limit = 2)
        check(parts.size == 2) { "Malformed SHA256SUMS line: $line" }
        parts[1] to parts[0]
    }
    check(checksums.keys == assets.listDirectoryEntries().map { it.name }.filter { it != "SHA256SUMS" }.toSet()) {
        "SHA256SUMS does not list exactly the staged assets"
    }
    for ((name, checksum) in checksums) {
        check(sha256(assets.resolve(name).readBytes()) == checksum) { "Checksum mismatch: $name" }
    }
    val metadata = Json.parseToJsonElement(assets.resolve("lab-bench-$version.module").readText()).jsonObject
    for (variant in metadata.getValue("variants").jsonArray) {
        for (dependency in variant.jsonObject["dependencies"]?.jsonArray.orEmpty()) {
            val group = dependency.string("group")
            if (group !in setOf("org.jetbrains.kotlin", "org.jetbrains.kotlinx")) {
                throw AssertionError("Unexpected SDK dependency: $group:${dependency.string("module")}")
            }
        }
    }
    ZipFile(assets.resolve("lab-bench-$version.jar").toFile()).use { archive ->
        val classes = archive.entries().asSequence().filter { it.name.endsWith(".class") }.toList()
        check(classes.isNotEmpty()) { "SDK JAR contains no classes" }
        for (entry in classes) {
            val header = archive.getInputStream(entry).use { it.readNBytes(8) }
            val major = header.drop(6).fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 0xff) }
            check(major <= 61) { "Requires newer than Java 17: ${entry.name}" }
        }
    }

    val root = Files.createTempDirectory("lab-bench-consumer-")
    try {
        consume(root, assets, version, gradle)
    } finally {
        root.toFile().deleteRecursively()
    }
}

private fun consume(root: Path, assets: Path, version: String, gradle: Path) {
    val release = root.resolve("http/releases/download/lab-bench-v$version").createDirectories()
    for (artifact in assets.listDirectoryEntries()) {
        Files.copy(artifact, release.resolve(artifact.name), StandardCopyOption.COPY_ATTRIBUTES)
    }
    val server = SimpleFileServer.createFileServer(
        InetSocketAddress(InetAddress.getLoopbackAddress(), 0), root.resolve("http"), SimpleFileServer.OutputLevel.NONE,
    )
    val threads = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
    server.executor = threads
    server.start()
    try {
        val consumer = root.resolve("consumer").createDirectories()
        consumer.resolve("settings.gradle.kts").writeText("rootProject.name = \"sdk-consumer\"\n")
        consumer.resolve("build.gradle.kts").writeText(
            CONSUMER_BUILD.replace("PORT", server.address.port.toString()).replace("VERSION", version),
        )
        val source = consumer.resolve("src/main/java/Consumer.java")
        source.parent.createDirectories()
        source.writeText(CONSUMER_SOURCE)
        val environment = System.getenv().filterKeys { key ->
            CREDENTIAL_WORDS.none { it in key.uppercase() } && key !in JVM_OPTION_VARIABLES
        }
        run(
            listOf(
                gradle.toString(), "--no-daemon", "--console=plain", "--stacktrace",
                "--gradle-user-home", root.resolve("gradle-home").toString(), "-p", consumer.toString(), "run", "installDist",
            ),
            environment, timeoutSeconds = 240,
        )
        val launcher = consumer.resolve("build/install/sdk-consumer/bin/sdk-consumer").toString()
        val descriptor = run(listOf(launcher, "describe"), environment, timeoutSeconds = 30, capture = true)
        check(Json.parseToJsonElement(descriptor.stdout).string("id") == "consumer") { descriptor.stdout }
        val request = root.resolve("request.json")
        request.writeText(buildJsonObject {
            put("protocolVersion", 1)
            put("parameters", buildJsonObject { put("seed", "139") })
        }.toString())
        val planned = run(listOf(launcher, "plan", request.toString()), environment, timeoutSeconds = 30, capture = true)
        val workload = Json.parseToJsonElement(planned.stdout).jsonObject.getValue("cases").jsonArray[0].jsonObject
        check(workload["parameters"] == buildJsonObject { put("seed", "139") }) { planned.stdout }
        val case = root.resolve("case.json")
        case.writeText(workload.toString())
        val output = root.resolve("retained")
        val withOutput = environment + ("OUT" to output.toString())
        run(listOf(launcher, "run", case.toString()), withOutput, timeoutSeconds = 30)
        var result = Json.parseToJsonElement(output.resolve("result.json").readText()).jsonObject
        check(
            result.string("status") == "success" &&
                result.getValue("metrics").jsonArray[0].jsonObject.getValue("value").jsonPrimitive.double == 42.0,
        ) { result.toString() }
        val artifact = result.getValue("artifacts").jsonArray[0].string("path")
        check(output.resolve(artifact).readText() == "retained") { "retained capture differs" }
        val failing = JsonObject(
            workload + ("parameters" to JsonObject(workload.getValue("parameters").jsonObject + ("fail" to JsonPrimitive("true")))),
        )
        case.writeText(failing.toString())
        val failed = run(listOf(launcher, "run", case.toString()), withOutput, timeoutSeconds = 30, capture = true, checked = false)
        check(failed.exitCode != 0) { "fixture failure exited 0" }
        result = Json.parseToJsonElement(output.resolve("result.json").readText()).jsonObject
        check(result.string("status") == "failed" && result.string("message") == "expected fixture failure") {
            result.toString()
        }
        println("SDK_PROCESS_CONTRACT_OK")
        System.out.flush()
    } finally {
        server.stop(0)
        threads.shutdownNow()
    }
}

private class Finished(val exitCode: Int, val stdout: String)

/**
 * Runs [command] with exactly [environment], killing it after [timeoutSeconds]. Captured output is returned (stderr
 * is read too, so a chatty child never blocks); otherwise the child shares this process's streams.
 */
private fun run(
    command: List<String>, environment: Map<String, String>, timeoutSeconds: Long,
    capture: Boolean = false, checked: Boolean = true,
): Finished {
    val builder = ProcessBuilder(command)
    builder.environment().clear()
    builder.environment().putAll(environment)
    if (capture) {
        builder.redirectInput(ProcessBuilder.Redirect.INHERIT)
    } else {
        builder.inheritIO()
    }
    val process = builder.start()
    val stdout = StringBuilder()
    val readers = if (capture) {
        listOf(
            Thread { stdout.append(process.inputStream.bufferedReader().readText()) },
            Thread { process.errorStream.bufferedReader().readText() },
        ).onEach { it.isDaemon = true; it.start() }
    } else {
        emptyList()
    }
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly().waitFor()
        error("timed out after $timeoutSeconds seconds: ${command.joinToString(" ")}")
    }
    readers.forEach { it.join() }
    val exitCode = process.exitValue()
    check(!checked || exitCode == 0) { "exit $exitCode: ${command.joinToString(" ")}" }
    return Finished(exitCode, stdout.toString())
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun JsonElement.string(key: String): String = jsonObject.getValue(key).jsonPrimitive.content

private val CONSUMER_BUILD = """
plugins { application }
repositories {
    exclusiveContent {
        forRepository {
            ivy {
                url = uri("http://127.0.0.1:PORT/releases/download")
                isAllowInsecureProtocol = true
                patternLayout { artifact("lab-bench-v[revision]/[artifact]-[revision](-[classifier]).[ext]") }
                metadataSources { gradleMetadata() }
            }
        }
        filter { includeModule("com.eignex", "lab-bench") }
    }
    mavenCentral()
}
dependencies { implementation("com.eignex:lab-bench:VERSION") }
application { mainClass.set("Consumer") }
"""

private val CONSUMER_SOURCE = """
import com.eignex.lab.bench.*;
import java.nio.file.*;
import java.util.*;
public class Consumer {
    private static final BenchSuite SUITE = new BenchSuite() {
        public BenchDescriptor describe() { return new BenchDescriptor("consumer", "Independent build", 1); }
        public List<BenchCase> plan(BenchRequest request) {
            return List.of(new BenchCase("smoke", request.getParameters(), new BenchResources(1, 256, 30, Set.of()), 1));
        }
        public BenchResult run(BenchCase workload, BenchContext context) {
            if (workload.getParameters().containsKey("fail")) throw new IllegalStateException("expected fixture failure");
            try { Files.writeString(context.getOutputDirectory().resolve("capture.txt"), "retained"); }
            catch (java.io.IOException failure) { throw new RuntimeException(failure); }
            return new BenchResult(workload.getId(), BenchStatus.SUCCESS,
                List.of(new BenchMetric("fixture", 42.0, "count", MetricDirection.NONE)),
                List.of(context.artifact("capture.txt", "text/plain", "Fixture capture")), null, null, 1);
        }
    };
    public static void main(String[] args) {
        if (args.length != 0) {
            BenchCli.INSTANCE.execute(SUITE, args, System.getenv(), System.out);
            return;
        }
        BenchDescriptor descriptor = new BenchDescriptor("consumer", "Independent build", 1);
        String json = BenchProtocol.INSTANCE.getJson().encodeToString(BenchDescriptor.Companion.serializer(), descriptor);
        BenchDescriptor decoded = BenchProtocol.INSTANCE.getJson().decodeFromString(BenchDescriptor.Companion.serializer(), json);
        if (!decoded.getId().equals("consumer")) throw new AssertionError(json);
        if (BenchProtocol.VERSION != 1) throw new AssertionError("protocol");
        System.out.println("SDK_CONSUMER_OK");
    }
}
"""
