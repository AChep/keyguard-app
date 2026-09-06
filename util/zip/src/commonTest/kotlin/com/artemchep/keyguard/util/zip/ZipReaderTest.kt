package com.artemchep.keyguard.util.zip

import kotlinx.coroutines.test.runTest
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSWORD = "correct horse"

private const val LARGE_ENTRY_SIZE = 300 * 1024

/** The prefix of the files the Apple reader spools archives into. */
private const val TEMP_FILE_PREFIX = "keyguard-zip-read-"

class ZipReaderTest {
    @Test
    fun readsBackAPlainArchive() = runTest {
        assertRoundTrip(password = null)
    }

    @Test
    fun readsBackAnEncryptedArchive() = runTest {
        assertRoundTrip(password = PASSWORD)
    }

    @Test
    fun visitsEntriesInTheOrderTheyWereWritten() = runTest {
        val names = listOf("a.txt", "nested/b.txt", "nested/dir/c.txt", "z.txt")
        val archive = archive(
            password = null,
            entries = names.map { name -> textEntry(name, text = name) },
        )

        val read = mutableListOf<String>()
        ZipReader(archive.source()).use { reader ->
            while (true) {
                val entry = reader.nextEntry() ?: break
                read += entry.name
            }
        }
        assertEquals(names, read)
    }

    @Test
    fun rejectsAWrongPassword() = runTest {
        val archive = encryptedArchive()

        // zip4j and the native reader fail with different types at different
        // moments; only the outcome matters.
        assertFails {
            ZipReader(archive.source(), "wrong password").use { reader ->
                reader.nextEntry()?.source?.readByteArray()
            }
        }
    }

    @Test
    fun rejectsAnEncryptedArchiveOpenedWithoutAPassword() = runTest {
        val archive = encryptedArchive()

        assertFails {
            ZipReader(archive.source()).use { reader ->
                reader.nextEntry()?.source?.readByteArray()
            }
        }
    }

    @Test
    fun closingAnEntrySourceLeavesTheReaderUsable() = runTest {
        val archive = archive(
            password = null,
            entries = listOf(
                textEntry("first.txt", "first"),
                textEntry("second.txt", "second"),
            ),
        )

        ZipReader(archive.source()).use { reader ->
            val first = reader.nextEntry()
            assertEquals("first.txt", first?.name)
            first?.source?.close()

            val second = reader.nextEntry()
            assertEquals("second.txt", second?.name)
            assertEquals("second", second?.source?.readString())
        }
    }

    @Test
    fun keepsReportingTheEndOfAnExhaustedArchive() = runTest {
        val archive = archive(
            password = null,
            entries = listOf(textEntry("only.txt", "only")),
        )

        ZipReader(archive.source()).use { reader ->
            assertEquals("only.txt", reader.nextEntry()?.name)
            assertNull(reader.nextEntry(), "the archive is exhausted")
            assertNull(reader.nextEntry(), "the archive stays exhausted")
        }
    }

    @Test
    fun closingTwiceIsHarmless() = runTest {
        val archive = archive(
            password = null,
            entries = listOf(textEntry("only.txt", "only")),
        )

        val reader = ZipReader(archive.source())
        assertEquals("only.txt", reader.nextEntry()?.name)
        reader.close()
        reader.close()
    }

    @Test
    fun leavesNoTemporaryFileBehind() = runTest {
        val before = spooledArchiveNames()
        val archive = archive(
            password = PASSWORD,
            entries = listOf(textEntry("only.txt", "only")),
        )

        ZipReader(archive.source(), PASSWORD).use { reader ->
            assertEquals("only.txt", reader.nextEntry()?.name)
        }

        val leaked = spooledArchiveNames() - before
        assertTrue(leaked.isEmpty(), "the reader left $leaked behind")
    }
}

private suspend fun assertRoundTrip(password: String?) {
    val blob = largePayload(LARGE_ENTRY_SIZE)
    val archive = archive(
        password = password,
        entries = listOf(
            textEntry("hello.txt", "hello world"),
            ZipEntry(
                name = "nested/dir/data.bin",
                data = ZipEntry.Data.Out { sink -> sink.write(blob) },
            ),
            textEntry("empty.txt", ""),
        ),
    )

    ZipReader(archive.source(), password).use { reader ->
        val hello = reader.nextEntry()
        assertEquals("hello.txt", hello?.name)
        assertEquals("hello world", hello?.source?.readString())

        val data = reader.nextEntry()
        assertEquals("nested/dir/data.bin", data?.name)
        assertContentEquals(blob, data?.source?.readByteArray())

        val empty = reader.nextEntry()
        assertEquals("empty.txt", empty?.name)
        assertContentEquals(ByteArray(0), empty?.source?.readByteArray())

        assertNull(reader.nextEntry(), "the archive is exhausted")
    }
}

private suspend fun encryptedArchive(): ByteArray = archive(
    password = PASSWORD,
    entries = listOf(textEntry("secret.txt", "secret")),
)

private fun spooledArchiveNames(): Set<String> = SystemFileSystem
    .list(SystemTemporaryDirectory)
    .map { path -> path.name }
    .filter { name -> name.startsWith(TEMP_FILE_PREFIX) }
    .toSet()
