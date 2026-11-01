package com.artemchep.keyguard.common.service.imports

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered

class ImportArchiveFailureTest {
    @Test
    fun `an intact archive imports successfully`() = runTest {
        assertIs<ImportResult.Success>(parse(BytesImportInput(fixture())))
    }

    @Test
    fun `corrupt archives report parse errors`() = runTest {
        val archives = listOf(
            fixture().copyOf(12), // Truncated local header.
            fixture().copyOf(44), // Truncated compressed payload.
            fixture().apply { this[40] = 7 }, // Invalid DEFLATE block type.
            fixture().apply {
                this[14] = 0 // Wrong CRC in the local header.
                this[70] = 0 // And in the central directory.
            },
        )
        for (bytes in archives) {
            assertEquals(ImportResult.Failure(ImportError.Parse), parse(BytesImportInput(bytes)))
        }
    }

    @Test
    fun `a file that cannot be opened reports unreadable`() = runTest {
        val input = object : ImportInput {
            override val name: String? = null
            override fun open(): Source = throw IOException("File unavailable")
        }
        assertEquals(ImportResult.Failure(ImportError.Unreadable), parse(input))
    }

    @Test
    fun `source read failures report unreadable before and during an entry`() = runTest {
        for (offset in listOf(0, 44)) {
            val input = object : ImportInput {
                override val name: String? = null
                override fun open(): Source {
                    val bytes = Buffer().apply { write(fixture()) }
                    var remaining = offset.toLong()
                    return object : RawSource {
                        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                            if (remaining == 0L) throw IOException("File unavailable")
                            return bytes.readAtMostTo(sink, minOf(byteCount, remaining))
                                .also { remaining -= it }
                        }

                        override fun close() = bytes.close()
                    }.buffered()
                }
            }
            assertEquals(ImportResult.Failure(ImportError.Unreadable), parse(input))
        }
    }

    private suspend fun parse(input: ImportInput): ImportResult = importTestService().parse(
        format = ImportFormatId.KeyguardZip,
        input = input,
        now = IMPORT_TEST_NOW,
    )
}

/** The same fixed DEFLATE archive exercised by util/zip's cross-platform failure tests. */
private fun fixture(): ByteArray = Base64.decode(
    "UEsDBBQAAAAIAJ1dXl0dH0PqDgAAAAwAAAAKAAAAdmF1bHQuanNvbqtWyixJzS1WsoqOrQUA" +
        "UEsBAhQDFAAAAAgAnV1eXR0fQ+oOAAAADAAAAAoAAAAAAAAAAAAAAIABAAAAAHZhdWx0Lmpzb25" +
        "QSwUGAAAAAAEAAQA4AAAANgAAAAAA",
)
