package com.eignex.lab

import com.eignex.lab.util.*
import kotlin.test.Test
import kotlin.test.assertEquals

class HashesTest {
    @Test
    fun `sha256Hex is lowercase hex of the UTF-8 bytes`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc"))
        assertEquals(sha256Hex("ä€".toByteArray(Charsets.UTF_8)), sha256Hex("ä€"))
        val bytes = ByteArray(256) { it.toByte() }
        assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, sha256Hex(bytes))
    }
}
