package com.artemchep.keyguard.util.zip

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val ZIP_SIGNATURE_PREFIX = byteArrayOf(0x50, 0x4b)

private val LOCAL_FILE_HEADER_SIGNATURE = byteArrayOf(0x50, 0x4b, 0x03, 0x04)

private val END_OF_CENTRAL_DIRECTORY_SIGNATURE = byteArrayOf(0x50, 0x4b, 0x05, 0x06)

/** The little-endian `0x9901` extra field id of WinZip AE encryption. */
private val AES_EXTRA_FIELD_ID = byteArrayOf(0x01, 0x99.toByte())

private const val LARGE_ENTRY_SIZE = 200 * 1024

class ZipServiceTest {
    @Test
    fun writesAPlainArchiveWithoutClosingTheSink() = runTest {
        val destination = RecordingSink()
        val sink = destination.buffered()

        createZipService().zip(
            outputStream = sink,
            config = ZipConfig(),
            entries = listOf(
                textEntry("hello.txt", "hello world"),
                ZipEntry(
                    name = "nested/dir/data.bin",
                    data = ZipEntry.Data.Out { entrySink ->
                        entrySink.write(largePayload(LARGE_ENTRY_SIZE))
                    },
                ),
            ),
        )

        val archive = destination.snapshot()
        assertTrue(archive.startsWith(ZIP_SIGNATURE_PREFIX), "archive starts with PK")
        assertTrue(
            archive.containsSequence(LOCAL_FILE_HEADER_SIGNATURE),
            "archive holds local file headers",
        )
        assertTrue(
            archive.containsSequence(END_OF_CENTRAL_DIRECTORY_SIGNATURE),
            "archive ends with a central directory",
        )
        assertTrue(
            archive.containsSequence("hello.txt".encodeToByteArray()),
            "archive names the first entry",
        )
        assertTrue(
            archive.containsSequence("nested/dir/data.bin".encodeToByteArray()),
            "archive names the second entry",
        )

        assertFalse(destination.closed, "the sink stays open")
        sink.writeByte(0x7f)
        sink.flush()
        assertContentEquals(
            byteArrayOf(0x7f),
            destination.snapshot().copyOfRange(archive.size, archive.size + 1),
        )
    }

    @Test
    fun encryptsEveryEntryOfAnEncryptedArchive() = runTest {
        val plaintext = ByteArray(64) { index -> (index * 7 + 11).toByte() }
        val destination = RecordingSink()
        val sink = destination.buffered()

        createZipService().zip(
            outputStream = sink,
            config = ZipConfig(
                encryption = ZipConfig.Encryption("correct horse"),
            ),
            entries = listOf(
                bytesEntry("secret.bin", plaintext),
            ),
        )

        val archive = destination.snapshot()
        assertTrue(
            archive.containsSequence(AES_EXTRA_FIELD_ID),
            "archive carries the AES extra field",
        )
        assertFalse(
            archive.containsSequence(plaintext),
            "archive does not carry the plaintext",
        )
    }

    @Test
    fun writesAnEmptyArchiveWithoutEntries() = runTest {
        val destination = RecordingSink()
        val sink = destination.buffered()

        createZipService().zip(
            outputStream = sink,
            config = ZipConfig(),
            entries = emptyList(),
        )

        val archive = destination.snapshot()
        assertTrue(
            archive.containsSequence(END_OF_CENTRAL_DIRECTORY_SIGNATURE),
            "archive has a central directory",
        )
        assertFalse(
            archive.containsSequence(LOCAL_FILE_HEADER_SIGNATURE),
            "archive has no entries",
        )
    }
}

/** A plain [Buffer] cannot report a `close`; this sink does. */
private class RecordingSink : RawSink {
    private val buffer = Buffer()

    var closed: Boolean = false
        private set

    override fun write(source: Buffer, byteCount: Long) {
        check(!closed) { "The sink is closed" }
        buffer.write(source, byteCount)
    }

    override fun flush() {
        check(!closed) { "The sink is closed" }
    }

    override fun close() {
        closed = true
    }

    fun snapshot(): ByteArray = buffer.copy().readByteArray()
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

private fun ByteArray.containsSequence(needle: ByteArray): Boolean = when {
    needle.isEmpty() -> true
    needle.size > size -> false
    else -> (0..size - needle.size).any { start ->
        needle.indices.all { index -> this[start + index] == needle[index] }
    }
}
