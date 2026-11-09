package com.eignex.lab.bench

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BenchInputTreesTest {
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `a tree checksum covers each file path and checksum in path order`() {
        val files = mapOf("lib/g.mzn" to sha("g"), "model.mzn" to sha("m"))
        assertEquals(sha("lib/g.mzn\u0000${sha("g")}\u0000model.mzn\u0000${sha("m")}\u0000"), BenchInputTrees.sha256(files))
        assertEquals(BenchInputTrees.sha256(files), BenchInputTrees.sha256(files.toList().reversed().toMap()))
        assertFailsWith<IllegalArgumentException> { BenchInputTrees.sha256(emptyMap()) }
        assertFailsWith<IllegalArgumentException> { BenchInputTrees.sha256(mapOf("../g.mzn" to sha("g"))) }
    }

    @Test
    fun `a declared tree names only declared files below it`() {
        val model = BenchInput("p/model.mzn", BenchInputRoot.CORPUS, "model", sha("m"))
        val tree = BenchInputTrees.declare("p", listOf(model))
        assertEquals(BenchInput("p", BenchInputRoot.CORPUS, "include", BenchInputTrees.sha256(mapOf("model.mzn" to sha("m")))), tree)
        assertFailsWith<IllegalArgumentException> { BenchInputTrees.declare("q", listOf(model)) }
        assertFailsWith<IllegalArgumentException> { BenchInputTrees.declare("p", listOf(model.copy(sha256 = null))) }
        assertFailsWith<IllegalArgumentException> { BenchInputTrees.declare("p", listOf(model, model.copy(sha256 = sha("other")))) }
        assertFailsWith<IllegalArgumentException> { BenchInputTrees.declare("p", listOf(model.copy(root = BenchInputRoot.SOURCE))) }
    }
}
