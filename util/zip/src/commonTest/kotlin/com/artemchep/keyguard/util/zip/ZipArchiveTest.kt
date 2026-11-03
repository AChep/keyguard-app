package com.artemchep.keyguard.util.zip

import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZipArchiveTest {
    @Test
    fun listsWithoutPasswordAndReadsSelectedEntriesWithRetry() = runTest {
        for (password in listOf(null, "secret")) {
            val bytes = archive(password, listOf(textEntry("folder/привіт.txt", "hello"), textEntry("empty.txt", "")))
            ZipArchive(bytes).use { zip ->
                assertEquals(listOf("folder/привіт.txt", "empty.txt"), zip.entries.map { it.path })
                assertEquals(5, zip.entries.first().size)
                assertEquals(password != null, zip.entries.first().isEncrypted)
                assertEquals("", zip.openEntry(1, password, 5).use { it.readString() })
                if (password != null) {
                    assertEquals(ZipArchiveError.Password, assertFailsWith<ZipArchiveException> {
                        zip.openEntry(0, maxBytes = 5)
                    }.reason)
                    assertFailsWith<ZipArchiveException> { zip.openEntry(0, "wrong", 5).use { it.readString() } }
                }
                assertEquals("hello", zip.openEntry(0, password, 5).use { it.readString() })
            }
        }
    }

    @Test
    fun enforcesEntryCountAndExpandedLimits() = runTest {
        val bytes = archive(null, listOf(bytesEntry("large.txt", ByteArray(100_000) { 65 })))
        assertEquals(
            ZipArchiveError.TooManyEntries,
            assertFailsWith<ZipArchiveException> { ZipArchive(bytes, 0) }.reason,
        )
        ZipArchive(bytes).use { zip ->
            assertEquals(ZipArchiveError.TooLarge, assertFailsWith<ZipArchiveException> {
                zip.openEntry(0, maxBytes = 99_999)
            }.reason)
            assertEquals(100_000, zip.openEntry(0, maxBytes = 100_000).use { it.readByteArray().size })
        }
    }

    @Test
    fun rejectsForgedExpandedSizeAtReadTime() = runTest {
        val bytes = archive(null, listOf(bytesEntry("large.txt", ByteArray(100_000) { 65 })))
        val central = signature(bytes, 0x02014b50)
        if ((bytes[central + 24].toInt() and 255) == 255) {
            val nameSize = (bytes[central + 28].toInt() and 255) or ((bytes[central + 29].toInt() and 255) shl 8)
            put32(bytes, central + 46 + nameSize + 4, 1)
        } else put32(bytes, central + 24, 1)
        ZipArchive(bytes).use { zip ->
            assertFailsWith<ZipArchiveException> { zip.openEntry(0, maxBytes = 10).use { it.readByteArray() } }
        }
    }

    @Test
    fun rejectsTruncationMultipartAndMismatchedLocalHeaders() = runTest {
        val original = archive(null, listOf(textEntry("hello.txt", "hello")))
        assertFailsWith<ZipArchiveException> { ZipArchive(original.copyOf(original.size - 1)) }
        val multipart = original.copyOf().apply { this[size - 18] = 1 }
        assertEquals(ZipArchiveError.Unsupported, assertFailsWith<ZipArchiveException> { ZipArchive(multipart) }.reason)
        val mismatched = original.copyOf().apply { this[30] = 'x'.code.toByte() }
        assertFailsWith<ZipArchiveException> { ZipArchive(mismatched) }
    }

    @Test
    fun unsafePathsAndSymlinksCannotBeOpened() = runTest {
        for (name in listOf("../secret.txt", "/secret.txt", "C:/secret.txt", "a\\secret.txt")) {
            ZipArchive(archive(null, listOf(textEntry(name, "hello")))).use { zip ->
                assertFalse(zip.entries.single().isSafe)
                assertFailsWith<ZipArchiveException> { zip.openEntry(0, maxBytes = 100) }
            }
        }
        val symlink = archive(null, listOf(textEntry("link", "target")))
        put32(symlink, signature(symlink, 0x02014b50) + 38, 0xa0000000L)
        ZipArchive(symlink).use { assertFalse(it.entries.single().isSupported) }
    }

    @Test
    fun verifiesPlaintextCrcAndEncryptedAuthenticationBeforeEof() = runTest {
        for (password in listOf(null, "secret")) {
            val bytes = archive(password, listOf(textEntry("file.txt", "hello")))
            val central = signature(bytes, 0x02014b50)
            if (password == null) {
                bytes[central + 16] = (bytes[central + 16].toInt() xor 1).toByte()
            } else {
                val entry = ZipArchive(bytes).use { it.entries.single() }
                val nameSize = (bytes[26].toInt() and 255) or ((bytes[27].toInt() and 255) shl 8)
                val extraSize = (bytes[28].toInt() and 255) or ((bytes[29].toInt() and 255) shl 8)
                val last = 30 + nameSize + extraSize + entry.compressedSize.toInt() - 1
                bytes[last] = (bytes[last].toInt() xor 1).toByte()
            }
            ZipArchive(bytes).use { zip ->
                assertFailsWith<ZipArchiveException> {
                    zip.openEntry(0, password, 5).use { it.readByteArray() }
                }
            }
        }
    }

    @Test
    fun emptyArchivesAndResourceLifetime() = runTest {
        ZipArchive(archive(null, emptyList())).use { assertTrue(it.entries.isEmpty()) }
        val bytes = archive(null, listOf(textEntry("hello.txt", "hello")))
        val original = bytes.copyOf()
        val zip = ZipArchive(bytes)
        val source = zip.openEntry(0, maxBytes = 5)
        zip.close()
        zip.close()
        assertFailsWith<IllegalStateException> { source.readString() }
        assertFailsWith<IllegalStateException> { zip.openEntry(0, maxBytes = 5) }
        assertContentEquals(original, bytes)
    }

    @Test
    fun rejectsDuplicateNamesAndListsUnsupportedCompression() = runTest {
        val bytes = archive(null, listOf(textEntry("a.txt", "a"), textEntry("b.txt", "b")))
        val first = signature(bytes, 0x02014b50)
        val second = signature(bytes, 0x02014b50, first + 4)
        val duplicate = bytes.copyOf().apply { this[second + 46] = 'a'.code.toByte() }
        assertFailsWith<ZipArchiveException> { ZipArchive(duplicate) }
        val unsupported = archive(null, listOf(textEntry("a.txt", "a")))
        val central = signature(unsupported, 0x02014b50)
        unsupported[8] = 12
        unsupported[central + 10] = 12
        ZipArchive(unsupported).use { assertFalse(it.entries.single().isSupported) }
    }

    @Test
    fun ignoresZip64LocatorSignatureInsideDirectoryComment() = runTest {
        val original = archive(null, listOf(textEntry("file.txt", "hello")))
        val end = original.size - 22
        val central = signature(original, 0x02014b50)
        val comment = ByteArray(20)
        put32(comment, 0, 0x07064b50)
        val bytes = original.copyOfRange(0, end) + comment + original.copyOfRange(end, original.size)
        bytes[central + 32] = comment.size.toByte()
        put32(bytes, end + comment.size + 12, (end + comment.size - central).toLong())
        ZipArchive(bytes).use { zip ->
            assertEquals("hello", zip.openEntry(0, maxBytes = 5).use { it.readString() })
        }
    }

    @Test
    fun readsZip64DirectoryRecordsWithoutAllocatingFromTheirCounts() = runTest {
        val original = archive(null, listOf(textEntry("file.txt", "hello")))
        val end = original.size - 22
        val central = signature(original, 0x02014b50)
        val records = ByteArray(76)
        put32(records, 0, 0x06064b50)
        put32(records, 4, 44)
        put32(records, 24, 1)
        put32(records, 32, 1)
        put32(records, 40, (end - central).toLong())
        put32(records, 48, central.toLong())
        put32(records, 56, 0x07064b50)
        put32(records, 64, end.toLong())
        put32(records, 72, 1)
        for (sentinels in listOf(false, true)) {
            val tail = original.copyOfRange(end, original.size)
            if (sentinels) {
                repeat(4) { tail[8 + it] = 0xff.toByte() }
                put32(tail, 12, 0xffffffffL)
                put32(tail, 16, 0xffffffffL)
            }
            val bytes = original.copyOfRange(0, end) + records + tail
            ZipArchive(bytes).use { zip ->
                assertEquals("hello", zip.openEntry(0, maxBytes = 5).use { it.readString() })
            }
            val invalidLocator = bytes.copyOf()
            put32(invalidLocator, end + 64, bytes.size.toLong())
            assertEquals(ZipArchiveError.Invalid, assertFailsWith<ZipArchiveException> {
                ZipArchive(invalidLocator)
            }.reason)
            put32(bytes, end + 28, 1)
            put32(bytes, end + 36, 1)
            assertEquals(ZipArchiveError.TooManyEntries, assertFailsWith<ZipArchiveException> {
                ZipArchive(bytes)
            }.reason)
        }
    }

    private fun signature(bytes: ByteArray, value: Long, start: Int = 0): Int =
        (start..bytes.size - 4).first { p ->
            (0..3).all { bytes[p + it].toInt() and 255 == ((value ushr (it * 8)) and 255).toInt() }
        }

    private fun put32(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { bytes[offset + it] = (value ushr (it * 8)).toByte() }
    }
}
