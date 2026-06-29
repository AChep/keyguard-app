package com.artemchep.keyguard.util.foundation.io

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinxIoExtTest {
    @Test
    fun writeTextWritesUtf8BytesIntoSink() {
        val sink = Buffer()
        val data = "payload Privit"

        sink.writeText(data)

        assertContentEquals(data.encodeToByteArray(), sink.readByteArray())
    }

    @Test
    fun useLinesReturnsNoLinesForEmptySource() {
        val lines = Buffer().useLines { it.toList() }

        assertEquals(emptyList(), lines)
    }

    @Test
    fun useLinesReadsLfTerminatedLines() {
        val lines = Buffer()
            .apply {
                writeString("alpha\nbeta\n")
            }
            .useLines { it.toList() }

        assertEquals(listOf("alpha", "beta"), lines)
    }

    @Test
    fun useLinesReadsFinalLineWithoutTrailingNewline() {
        val lines = Buffer()
            .apply {
                writeString("alpha\nbeta")
            }
            .useLines { it.toList() }

        assertEquals(listOf("alpha", "beta"), lines)
    }

    @Test
    fun useLinesNormalizesCrlfLines() {
        val lines = Buffer()
            .apply {
                writeString("alpha\r\nbeta\r\n")
            }
            .useLines { it.toList() }

        assertEquals(listOf("alpha", "beta"), lines)
    }

    @Test
    fun useLinesClosesSourceAfterBlock() {
        val upstream = object : RawSource {
            private val buffer = Buffer().apply {
                writeString("alpha")
            }
            var closed = false

            override fun readAtMostTo(
                sink: Buffer,
                byteCount: Long,
            ): Long = buffer.readAtMostTo(sink, byteCount)

            override fun close() {
                closed = true
            }
        }

        upstream.buffered().useLines { lines ->
            assertEquals(listOf("alpha"), lines.toList())
        }

        assertTrue(upstream.closed)
    }

    @Test
    fun readByteArrayAndCloseReturnsAllBytes() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)

        assertContentEquals(bytes, bytes.toSource().readByteArrayAndClose())
    }

    @Test
    fun writeByteArrayWritesBytes() {
        val sink = Buffer()

        sink.writeByteArray(byteArrayOf(10, 20, 30))

        assertContentEquals(byteArrayOf(10, 20, 30), sink.readByteArray())
    }

    @Test
    fun writeTextEncodesMultiByteUtf8() {
        val sink = Buffer()
        val text = "café — ü"

        sink.writeText(text)

        assertContentEquals(text.encodeToByteArray(), sink.readByteArray())
    }

    @Test
    fun useLinesPreservesEmptyLines() {
        val lines = Buffer()
            .apply {
                writeString("alpha\n\nbeta\n")
            }
            .useLines { it.toList() }

        assertEquals(listOf("alpha", "", "beta"), lines)
    }

    @Test
    fun stringToSourceRoundTrip() {
        assertContentEquals(
            "hi there".encodeToByteArray(),
            "hi there".toSource().readByteArrayAndClose(),
        )
    }
}
