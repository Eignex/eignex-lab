package com.eignex.lab.worker

import com.eignex.lab.util.hashFile
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.TimeUnit

fun main() {
    val root = Path.of(requireNotNull(System.getenv("LAB_GRADLE_SEED_ROOT")))
    Files.createDirectory(root)
    val source = Files.createDirectory(root.resolve("source"))
    val wrapper = source.resolve("gradle/wrapper")
    Files.createDirectories(wrapper)
    for (name in listOf("gradle-wrapper.properties", "gradle-wrapper.jar")) {
        Files.copy(Path.of("gradle/wrapper/$name"), wrapper.resolve(name))
    }
    val properties = Properties().also { Files.newInputStream(wrapper.resolve("gradle-wrapper.properties")).use(it::load) }
    val expected = properties.getProperty("distributionSha256Sum")
    val name = properties.getProperty("distributionUrl").substringAfterLast('/')
    val version = name.removePrefix("gradle-").substringBeforeLast('-')
    val home = root.resolve("home")
    val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3)
    fun remaining() = (deadline - System.nanoTime()) / 1_000_000
    val downloaded = requireNotNull(GradleDistributionSeed().stage(source, home, ::remaining))
    check(downloaded.downloaded && downloaded.sha256 == expected)
    val zip = Files.walk(home).use { files -> files.filter { it.fileName.toString() == name }.findFirst().orElseThrow() }
    check(hashFile(zip) == expected)
    val reused = requireNotNull(GradleDistributionSeed { _, _, _, _, _ -> error("verified archive requested network") }.stage(source, home, ::remaining))
    check(!reused.downloaded && reused.bytes == downloaded.bytes)
    val output = root.resolve("wrapper-version.txt")
    val child = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
        "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=1", "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=1",
        "-classpath", wrapper.resolve("gradle-wrapper.jar").toString(), "org.gradle.wrapper.GradleWrapperMain",
        "-g", home.toString(), "--version").directory(source.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start()
    try {
        check(child.waitFor(remaining().coerceAtLeast(1L), TimeUnit.MILLISECONDS)) { "offline wrapper verification timed out" }
        check(child.exitValue() == 0 && Files.readString(output).contains("Gradle $version")) { Files.readString(output) }
    } finally {
        if (child.isAlive) child.destroyForcibly()
    }
    val receipt = buildJsonObject {
        put("version", version); put("sha256", expected); put("bytes", downloaded.bytes)
        put("publishedDigestVerified", true); put("localBytesVerified", true)
        put("verifiedReuseWithoutNetwork", true); put("standardWrapperInstalledOffline", true)
        put("solverRun", false)
    }.toString()
    Files.writeString(root.resolve("gradle-seed-receipt.json"), receipt + "\n")
    println(receipt)
}
