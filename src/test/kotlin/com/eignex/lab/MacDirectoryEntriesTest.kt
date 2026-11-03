package com.eignex.lab

import com.eignex.lab.store.*
import com.eignex.lab.util.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.concurrent.CancellationException
import kotlin.test.*

class MacDirectoryEntriesTest {
    private fun record(name: String = "fixture"): ByteArray {
        val encoded = name.toByteArray()
        val length = (96 + encoded.size + 1 + 7) / 8 * 8
        val bytes = ByteArray(length)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        buffer.putInt(0, length); buffer.putInt(4, 0x02200c0b); buffer.putInt(16, 0x200)
        buffer.putInt(28, 68); buffer.putInt(32, encoded.size + 1)
        buffer.putInt(36, 0x1000005); buffer.putInt(40, 1)
        buffer.putLong(44, 1793570000); buffer.putLong(52, 123456789)
        buffer.putLong(60, 1793570001); buffer.putLong(68, 987654321)
        buffer.putInt(76, 1); buffer.putLong(80, 12345); buffer.putLong(88, 17)
        encoded.copyInto(bytes, 96)
        return bytes
    }

    private fun listingRecord(name: String = "fixture", type: Int = 1): ByteArray {
        val encoded = name.toByteArray()
        val fixed = if (type == 1) 48 else 40
        val bytes = ByteArray((fixed + encoded.size + 1 + 7) / 8 * 8)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        buffer.putInt(0, bytes.size); buffer.putInt(4, 9)
        buffer.putInt(16, if (type == 1) 0x200 else 0)
        buffer.putInt(28, fixed - 28); buffer.putInt(32, encoded.size + 1); buffer.putInt(36, type)
        if (type == 1) buffer.putLong(40, 17)
        encoded.copyInto(bytes, fixed)
        return bytes
    }

    @Test fun `listing records represent only names types and lengths including directories and links`() {
        val records = listingRecord("é-雪") + listingRecord("directory", 2) + listingRecord("link", 5) + listingRecord(".", 2)
        val entries = assertNotNull(MacDirectoryEntries.decodeListing(records, 4))
        assertEquals(listOf(MacDirectoryEntries.ListingEntry("é-雪", false, true, 17),
            MacDirectoryEntries.ListingEntry("directory", true, false, 0),
            MacDirectoryEntries.ListingEntry("link", false, false, 0)), entries)
        assertNull(MacDirectoryEntries.decode(records, 4), "listing hints must never satisfy strong verification metadata")
        val partial = listingRecord("file").copyOfRange(0, 48)
        assertNull(MacDirectoryEntries.decodeListing(partial, 1))
    }

    @Test fun `listing rejects missing attributes errors unsupported layouts and malformed name bounds`() {
        fun rejected(change: (ByteBuffer) -> Unit) {
            val bytes = listingRecord(); change(ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()))
            assertNull(MacDirectoryEntries.decodeListing(bytes, 1))
        }
        rejected { it.putInt(0, Int.MAX_VALUE) }
        rejected { it.putInt(0, 39) }
        rejected { it.putInt(0, 55) }
        rejected { it.putInt(4, 8) }
        rejected { it.putInt(4, 1) }
        rejected { it.putInt(4, 11) }
        rejected { it.putInt(8, 1) }
        rejected { it.putInt(12, 1) }
        rejected { it.putInt(16, 0) }
        rejected { it.putInt(16, 0x201) }
        rejected { it.putInt(20, 1) }
        rejected { it.putInt(24, 5) }
        rejected { it.putInt(28, Int.MIN_VALUE) }
        rejected { it.putInt(28, 19) }
        rejected { it.putInt(28, 21) }
        rejected { it.putInt(32, Int.MAX_VALUE) }
        rejected { it.putInt(32, 0) }
        rejected { it.putLong(40, -1) }
        assertNull(MacDirectoryEntries.decodeListing(listingRecord().copyOf(39), 1))
        assertNull(MacDirectoryEntries.decodeListing(listingRecord(), 2))
        assertNull(MacDirectoryEntries.decodeListing(listingRecord("unsafe/name"), 1))
        assertNull(MacDirectoryEntries.decodeListing(listingRecord("unsafe\u0000name"), 1))
        assertNull(MacDirectoryEntries.decodeListing(listingRecord(""), 1))
        val unterminated = listingRecord(); unterminated[48 + "fixture".length] = 1
        assertNull(MacDirectoryEntries.decodeListing(unterminated, 1))
    }

    @Test fun `native minimal listings match ordinary no-follow attributes across multiple buffers`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("mac"))
        val root = Files.createTempDirectory("mac-listing-metadata")
        try {
            repeat(1400) { Files.writeString(root.resolve("file-$it"), "value-$it") }
            Files.writeString(root.resolve("é-雪"), "unicode")
            Files.createDirectory(root.resolve("directory"))
            Files.createSymbolicLink(root.resolve("file-link"), root.resolve("file-0"))
            Files.createSymbolicLink(root.resolve("dir-link"), root.resolve("directory"))
            val entries = assertNotNull(MacDirectoryEntries.readListing(root, {}))
            assertEquals(1404, entries.size)
            for (entry in entries) {
                val expected = Files.readAttributes(root.resolve(entry.name), java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                assertEquals(expected.isDirectory, entry.directory, entry.name)
                assertEquals(expected.isRegularFile, entry.regular, entry.name)
                assertEquals(if (expected.isRegularFile) expected.size() else 0L, entry.bytes, entry.name)
            }
            val ordinary = com.eignex.lab.domain.jobFiles(root) { _, _ -> null }
            assertEquals(ordinary, com.eignex.lab.domain.jobFiles(root), "native hints retain the complete ordinary inventory")
            assertFalse(entries.single { it.name == "file-link" }.regular)
            assertFalse(entries.single { it.name == "dir-link" }.directory)
            assertNull(MacDirectoryEntries.readListing(root.resolve("file-link"), {}))
            assertNull(MacDirectoryEntries.readListing(root.resolve("dir-link"), {}))
            var calls = 0
            assertFailsWith<CancellationException> {
                MacDirectoryEntries.readListing(root) { if (++calls == 2) throw CancellationException("listing ended") }
            }
            assertNotNull(MacDirectoryEntries.readListing(root, {}))
        } finally { deleteTree(root) }
    }

    @Test fun `packed metadata handles unaligned times unicode and multiple records`() {
        val entries = assertNotNull(MacDirectoryEntries.decode(record("é-雪") + record("second"), 2))
        assertEquals(listOf("é-雪", "second"), entries.map { it.name })
        val first = entries.first()
        assertTrue(first.regular); assertFalse(first.directory); assertTrue(first.executable)
        assertEquals(17L, first.bytes); assertEquals("(dev=1000005,ino=12345)", first.key)
        assertEquals(java.nio.file.attribute.FileTime.from(java.time.Instant.ofEpochSecond(1793570000, 123456789)).toString(), first.modified)
    }

    @Test fun `directories omit type-specific file fields even when invalid attributes are packed`() {
        val name = "directory".toByteArray()
        val bytes = record().copyOf((88 + name.size + 1 + 7) / 8 * 8)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        buffer.putInt(0, bytes.size); buffer.putInt(16, 0); buffer.putInt(28, 60)
        buffer.putInt(32, name.size + 1); buffer.putInt(40, 2)
        bytes.fill(0, 88); name.copyInto(bytes, 88)
        val entry = assertNotNull(MacDirectoryEntries.decode(bytes + record("file"), 2)).first()
        assertEquals("directory", entry.name); assertTrue(entry.directory); assertFalse(entry.regular); assertEquals(0L, entry.bytes)
    }

    @Test fun `invalid masks errors lengths names and times cannot become verification observations`() {
        fun rejected(change: (ByteBuffer) -> Unit) {
            val bytes = record(); change(ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()))
            assertNull(MacDirectoryEntries.decode(bytes, 1))
        }
        rejected { it.putInt(0, Int.MAX_VALUE) }
        rejected { it.putInt(0, 95) }
        rejected { it.putInt(4, 0) }
        rejected { it.putInt(16, 0) }
        rejected { it.putInt(24, 5) }
        rejected { it.putInt(28, Int.MIN_VALUE) }
        rejected { it.putInt(32, Int.MAX_VALUE) }
        rejected { it.putLong(52, -1) }
        rejected { it.putLong(68, 1000000000) }
        rejected { it.putLong(88, -1) }
        assertNull(MacDirectoryEntries.decode(record().copyOf(95), 1))
        assertNull(MacDirectoryEntries.decode(record(), 2))
        assertNull(MacDirectoryEntries.decode(record("unsafe/name"), 1))
        assertNull(MacDirectoryEntries.decode(record("unsafe\u0000name"), 1))
    }

    @Test fun `native batches exactly match no-follow Java metadata and reuse existing receipts`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("mac"))
        val root = Files.createTempDirectory("mac-bulk-metadata")
        try {
            repeat(800) { Files.writeString(root.resolve("file-$it"), "value-$it") }
            Files.writeString(root.resolve("é-雪"), "unicode")
            val executable = root.resolve("file-0"); assertTrue(executable.toFile().setExecutable(true, true))
            val denied = root.resolve("file-1"); assertTrue(denied.toFile().setExecutable(true, true))
            val acl = ProcessBuilder("/bin/chmod", "+a", "user:${System.getProperty("user.name")} deny execute", denied.toString()).start()
            assertTrue(acl.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, acl.exitValue())
            assertFalse(Files.isExecutable(denied), "fixture must exercise effective ACL access, not only mode bits")
            Files.createDirectory(root.resolve("directory"))
            assertTrue(MacDirectoryEntries.supported(root))
            val entries = assertNotNull(MacDirectoryEntries.read(root, {}))
            assertEquals(802, entries.size, "the fixture spans more than one native result buffer")
            for (entry in entries) {
                val file = root.resolve(entry.name)
                val expected = Files.readAttributes(file, "unix:size,lastModifiedTime,ctime,fileKey,isRegularFile,isDirectory", NOFOLLOW_LINKS)
                assertEquals(expected["isDirectory"], entry.directory, entry.name)
                assertEquals(expected["isRegularFile"], entry.regular, entry.name)
                assertEquals(expected["lastModifiedTime"].toString(), entry.modified, entry.name)
                assertEquals(expected["ctime"].toString(), entry.changed, entry.name)
                assertEquals(expected["fileKey"].toString(), entry.key, entry.name)
                assertEquals(Files.isExecutable(file), entry.executable, entry.name)
                if (entry.regular) assertEquals(expected["size"], entry.bytes, entry.name)
            }
            val receipt = root.resolveSibling(root.fileName.toString() + ".json")
            try {
                val files = TreeVerification(bulkMetadata = false).capture(root, receipt)
                val bulk = TreeVerification(hash = { _, _ -> error("legacy receipts must remain checksum-free") })
                assertTrue(bulk.valid(root, files, receipt, exactExecutables = true))
                assertEquals(files.size, bulk.stats.bulkFiles); assertEquals(0, bulk.stats.readAheadFiles)
            } finally { Files.deleteIfExists(receipt) }
            Files.createSymbolicLink(root.resolve("link"), executable)
            val linked = assertNotNull(MacDirectoryEntries.read(root, {})).single { it.name == "link" }
            assertFalse(linked.regular); assertFalse(linked.directory)
            assertNull(MacDirectoryEntries.read(root.resolve("link"), {}))
            var calls = 0
            assertFailsWith<CancellationException> { MacDirectoryEntries.read(root) { if (++calls == 2) throw CancellationException("lease ended") } }
            assertNotNull(MacDirectoryEntries.read(root, {}))
        } finally { deleteTree(root) }
    }
}
