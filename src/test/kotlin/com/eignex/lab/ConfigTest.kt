package com.eignex.lab

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigTest {
    @Test
    fun `the environment's settings are read with their defaults`() {
        val data = Files.createTempDirectory("lab")
        val config = Config.fromEnv(mapOf("LAB_DATA" to data.toString(), "LAB_REQUIRE_DOCKER" to "yes", "LAB_TRIM_AGE_DAYS" to "7"))
        assertEquals(data, config.dataDir)
        assertEquals(data.resolve("jobs"), config.jobsDir)
        assertTrue(config.requireDocker)
        assertEquals(Defaults.MAX_PARALLEL, config.maxParallel)
        assertEquals(Defaults.MIN_FREE_GB * BYTES_PER_GB, config.trimCriticalFreeBytes)
        assertEquals(data.resolve("nomad/native.lock"), config.nativeExecutionLock)
        // The defaults a test's Config gets are the same ones; only what belongs to a process, its probes, differs.
        assertEquals(Config(dataDir = data).copy(auth = config.auth, requireDocker = true, cores = config.cores, javaHome = config.javaHome,
            volumeAccess = config.volumeAccess), config)
    }

    @Test
    fun `every bad setting is reported at once, each by its variable`() {
        val failure = assertFailsWith<ConfigException> {
            Config.fromEnv(mapOf(
                "LAB_CORES" to "0",
                "LAB_MAX_PARALLEL" to "zero",
                "LAB_MIN_FREE_GB" to "-1",
                "LAB_REQUIRE_DOCKER" to "maybe",
                "LAB_DEFAULT_PARALLEL" to "9",
                "LAB_TRIM_CRITICAL_FREE_GB" to "30",
                "LAB_JOBS_TARGET_FREE_GB" to "20",
                "LAB_RETRY_BASE_SEC" to "600",
            ))
        }
        val named = failure.problems.joinToString("\n")
        for (variable in listOf("LAB_CORES=0", "LAB_MAX_PARALLEL=zero", "LAB_MIN_FREE_GB=-1", "LAB_REQUIRE_DOCKER=maybe",
            "LAB_DEFAULT_PARALLEL=9", "LAB_TRIM_CRITICAL_FREE_GB (30) must not exceed LAB_JOBS_TARGET_FREE_GB (20)",
            "LAB_RETRY_BASE_SEC must not exceed LAB_RETRY_MAX_SEC")) {
            assertTrue(variable in named, named)
        }
    }

    @Test
    fun `a Nomad queue file that does not parse stops startup, not a submission`() {
        val data = Files.createTempDirectory("lab")
        val queue = data.resolve("queue.json").also { Files.writeString(it, "{ not json") }
        val failure = assertFailsWith<ConfigException> {
            Config.fromEnv(mapOf("LAB_DATA" to data.toString(), "LAB_NOMAD_QUEUE_SETTINGS" to queue.toString()))
        }
        assertTrue(failure.problems.single().startsWith("LAB_NOMAD_QUEUE_SETTINGS ($queue) does not parse"), failure.message)
    }
}
