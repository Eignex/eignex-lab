package com.eignex.lab

import com.eignex.lab.util.*
import com.eignex.lab.worker.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class GradleDistributionSeedTest {
    private val bytes = "verified distribution fixture".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private val name = "gradle-9.7.1-bin.zip"
    private val metadata = """{"assets":[{"name":"$name","id":123,"size":${bytes.size},"digest":"sha256:$sha"}]}"""
    private fun wrapper(root: Path) {
        val properties = root.resolve("gradle/wrapper/gradle-wrapper.properties")
        Files.createDirectories(properties.parent)
        Files.writeString(properties, "distributionUrl=https\\://services.gradle.org/distributions/$name\ndistributionSha256Sum=$sha\n")
    }
    private fun target(root: Path) = root.resolve("home/wrapper/dists/gradle-9.7.1-bin/1w1c7tv4s851m17nbqdsro2tv/$name")
    private fun source(metadata: String = this.metadata, content: ByteArray = bytes,
                       seen: MutableList<Pair<URI, String>> = mutableListOf()) = GradleDistributionSeed { uri, accept, path, _, _ ->
        seen.add(uri to accept)
        Files.write(path, if (uri.path.contains("/tags/")) metadata.toByteArray() else content)
        true
    }

    @Test fun `a pinned distribution is verified before the normal wrapper cache is populated`() {
        val root = Files.createTempDirectory("gradle-seed")
        try {
            wrapper(root); val seen = mutableListOf<Pair<URI, String>>()
            val receipt = source(seen = seen).stage(root, root.resolve("home")) { 10_000L }
            assertEquals(GradleDistributionSeed.Receipt(bytes.size.toLong(), sha, true), receipt)
            assertContentEquals(bytes, Files.readAllBytes(target(root)))
            assertEquals(listOf("application/vnd.github+json", "application/octet-stream"), seen.map { it.second })
            assertEquals("https://api.github.com/repos/gradle/gradle-distributions/releases/assets/123", seen.last().first.toString())
        } finally { deleteTree(root) }
    }

    @Test fun `a verified staged archive requires no further download`() {
        val root = Files.createTempDirectory("gradle-seed-cached")
        try {
            wrapper(root); val zip = target(root); Files.createDirectories(zip.parent); Files.write(zip, bytes)
            val seed = GradleDistributionSeed { _, _, _, _, _ -> error("download") }
            assertEquals(GradleDistributionSeed.Receipt(bytes.size.toLong(), sha, false), seed.stage(root, root.resolve("home")) { 10_000L })
        } finally { deleteTree(root) }
    }

    @Test fun `a published digest mismatch cannot replace an existing archive`() {
        val root = Files.createTempDirectory("gradle-seed-digest")
        try {
            wrapper(root); val zip = target(root); Files.createDirectories(zip.parent); Files.writeString(zip, "old")
            assertFailsWith<IllegalArgumentException> {
                source(metadata.replace(sha, "0".repeat(64))).stage(root, root.resolve("home")) { 10_000L }
            }
            assertEquals("old", Files.readString(zip))
            assertEquals(listOf(name), Files.list(zip.parent).use { it.map { path -> path.fileName.toString() }.sorted().toList() })
        } finally { deleteTree(root) }
    }

    @Test fun `corrupt downloaded bytes cannot be published`() {
        val root = Files.createTempDirectory("gradle-seed-corrupt")
        try {
            wrapper(root)
            assertFailsWith<IllegalArgumentException> { source(content = ByteArray(bytes.size)).stage(root, root.resolve("home")) { 10_000L } }
            assertFalse(Files.exists(target(root)))
            assertEquals(0, Files.list(target(root).parent).use { it.count().toInt() })
        } finally { deleteTree(root) }
    }

    @Test fun `an expired preparation cannot start a distribution download`() {
        val root = Files.createTempDirectory("gradle-seed-expired")
        try {
            wrapper(root)
            val seed = GradleDistributionSeed { _, _, _, _, _ -> error("download") }
            assertFailsWith<java.util.concurrent.CancellationException> { seed.stage(root, root.resolve("home")) { 0L } }
            assertFalse(Files.exists(target(root)))
        } finally { deleteTree(root) }
    }

    @Test fun `custom distribution URLs retain the wrapper download path`() {
        val root = Files.createTempDirectory("gradle-seed-custom")
        try {
            wrapper(root); val properties = root.resolve("gradle/wrapper/gradle-wrapper.properties")
            Files.writeString(properties, Files.readString(properties).replace("services.gradle.org", "example.invalid"))
            assertNull(GradleDistributionSeed { _, _, _, _, _ -> error("download") }.stage(root, root.resolve("home")) { 10_000L })
        } finally { deleteTree(root) }
    }

    @Test fun `a wrapper without a pinned checksum is not seeded`() {
        val root = Files.createTempDirectory("gradle-seed-unpinned")
        try {
            wrapper(root)
            val properties = root.resolve("gradle/wrapper/gradle-wrapper.properties")
            Files.writeString(properties, Files.readString(properties).substringBefore("distributionSha256Sum"))
            assertNull(GradleDistributionSeed { _, _, _, _, _ -> error("download") }.stage(root, root.resolve("home")) { 10_000L })
        } finally { deleteTree(root) }
    }

    @Test fun `an unavailable release leaves the original wrapper path usable`() {
        val root = Files.createTempDirectory("gradle-seed-unavailable")
        try {
            wrapper(root)
            assertNull(GradleDistributionSeed { _, _, _, _, _ -> false }.stage(root, root.resolve("home")) { 10_000L })
            assertFalse(Files.exists(target(root)))
            assertEquals(0, Files.list(target(root).parent).use { it.count().toInt() })
        } finally { deleteTree(root) }
    }

    @Test fun `malformed release metadata leaves the original wrapper path usable`() {
        val root = Files.createTempDirectory("gradle-seed-malformed")
        try {
            wrapper(root)
            for (bad in listOf("not json", "[]", """{"assets":[{"name":"$name","id":{}}]}""")) {
                assertNull(source(metadata = bad).stage(root, root.resolve("home")) { 10_000L })
            }
            assertFalse(Files.exists(target(root)))
        } finally { deleteTree(root) }
    }
}
