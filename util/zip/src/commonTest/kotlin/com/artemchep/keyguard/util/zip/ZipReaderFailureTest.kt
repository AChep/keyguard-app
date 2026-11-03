package com.artemchep.keyguard.util.zip

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.readString

class ZipReaderFailureTest {
    @Test
    fun readsTheUnmodifiedFixture() {
        ZipReader(fixture().source()).use { reader ->
            val entry = reader.nextEntry()
            assertEquals("vault.json", entry?.name)
            assertEquals("{\"items\":[]}", entry?.source?.readString())
        }
    }

    @Test
    fun normalizesCorruptHeadersAndPayloads() {
        for (bytes in corruptReaderArchives()) {
            assertFailsWith<ZipException> {
                ZipReader(bytes.source()).use { reader ->
                    while (true) {
                        val entry = reader.nextEntry() ?: break
                        entry.source.readByteArray()
                    }
                }
            }
        }
    }

    @Test
    fun preservesSourceFailuresBeforeAndDuringAnEntry() {
        for (offset in listOf(0, 44)) {
            val failure = IOException("Source unavailable")
            val caught = assertFailsWith<IOException> {
                ZipReader(failingSource(offset, failure)).use { reader ->
                    reader.nextEntry()?.source?.readByteArray()
                }
            }
            assertSame(failure, caught)
        }
    }

    @Test
    fun preservesSourceFailuresWhenSkippingAnEntry() {
        val failure = IOException("Source unavailable")
        val caught = assertFailsWith<IOException> {
            ZipReader(failingSource(44, failure)).use { reader ->
                while (reader.nextEntry() != null) {
                    // Leave the payload unread.
                }
            }
        }
        assertSame(failure, caught)
    }

    @Test
    fun preservesCancellation() {
        val failure = CancellationException("Cancelled")
        val caught = assertFailsWith<CancellationException> {
            ZipReader(failingSource(44, failure)).use { reader ->
                reader.nextEntry()?.source?.readByteArray()
            }
        }
        assertSame(failure, caught)
    }
}

internal fun corruptReaderArchives(): List<ByteArray> = listOf(
    fixture().copyOf(12), // Truncated local header.
    fixture().copyOf(44), // Truncated compressed payload.
    fixture().apply { this[40] = 7 }, // Reserved DEFLATE block type.
    fixture().apply {
        this[14] = 0 // Wrong CRC in the local header.
        this[70] = 0 // The same CRC in the central directory.
    },
)

private fun failingSource(offset: Int, failure: Exception): Source {
    val bytes = fixture().source()
    var remaining = offset.toLong()
    return object : RawSource {
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            if (remaining == 0L) throw failure
            return bytes.readAtMostTo(sink, minOf(byteCount, remaining)).also { remaining -= it }
        }

        override fun close() = bytes.close()
    }.buffered()
}

/** A fixed, unencrypted DEFLATE archive so every platform reads identical bytes. */
private fun fixture(): ByteArray = Base64.decode(
    "UEsDBBQAAAAIAJ1dXl0dH0PqDgAAAAwAAAAKAAAAdmF1bHQuanNvbqtWyixJzS1WsoqOrQUA" +
        "UEsBAhQDFAAAAAgAnV1eXR0fQ+oOAAAADAAAAAoAAAAAAAAAAAAAAIABAAAAAHZhdWx0Lmpzb25" +
        "QSwUGAAAAAAEAAQA4AAAANgAAAAAA",
)
