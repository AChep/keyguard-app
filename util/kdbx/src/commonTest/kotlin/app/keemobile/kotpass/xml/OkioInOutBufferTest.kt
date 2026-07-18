package app.keemobile.kotpass.xml

import app.keemobile.kotpass.errors.FormatError
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OkioInOutBufferTest {
    @Test
    fun validUtf8SequencesCanCrossEveryByteBoundary() {
        val values = listOf(
            "\u0000",
            "\u007F",
            "\u0080",
            "\u07FF",
            "\u0800",
            "\uD7FF",
            "\uE000",
            "\uFFFF",
            "\uD800\uDC00",
            "\uDBFF\uDFFF",
        )

        values.forEach { value ->
            val expected = "a${value}z"
            val bytes = expected.encodeToByteArray()
            val encodedValueSize = value.encodeToByteArray().size
            assertEquals(expected, decode(bytes), "value=${value.encodeToByteArray().toHex()}")
            for (split in 1 until encodedValueSize) {
                assertEquals(
                    expected,
                    decode(bytes, firstReadSize = 1 + split),
                    "value=${value.encodeToByteArray().toHex()} split=$split",
                )
            }
        }
    }

    @Test
    fun malformedUtf8SequencesAreRejected() {
        val cases = listOf(
            byteArrayOf(0x80.toByte()),
            byteArrayOf(0xC0.toByte(), 0xAF.toByte()),
            byteArrayOf(0xC1.toByte(), 0xBF.toByte()),
            byteArrayOf(0xF5.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xFF.toByte()),
            byteArrayOf(0xC2.toByte(), 0x20),
            byteArrayOf(0xE1.toByte(), 0x80.toByte(), 0x20),
            byteArrayOf(0xF1.toByte(), 0x80.toByte(), 0x80.toByte(), 0x20),
            byteArrayOf(0xC2.toByte()),
            byteArrayOf(0xE1.toByte(), 0x80.toByte()),
            byteArrayOf(0xF1.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            byteArrayOf(0xF0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
        )

        cases.forEach { bytes ->
            for (firstReadSize in 1..bytes.size) {
                assertFailsWith<FormatError.InvalidXml>(
                    "bytes=${bytes.toHex()} firstReadSize=$firstReadSize",
                ) {
                    decode(bytes, firstReadSize)
                }
            }
        }
    }

    @Test
    fun asciiRunsRemainCorrectAcrossDecodeAndCompactionBoundaries() {
        val expected = buildString {
            repeat(40_000) { index ->
                append(('a'.code + index % 26).toChar())
            }
            append(" — 漢字 — 😀")
        }

        assertEquals(expected, decode(expected.encodeToByteArray()))
    }

    private fun decode(
        bytes: ByteArray,
        firstReadSize: Int = bytes.size.coerceAtLeast(1),
    ): String {
        val source = FirstReadLimitedSource(bytes, firstReadSize).buffer()
        val input = OkioInOutBuffer(source, bytes.size.toLong())
        return buildString {
            while (true) {
                val char = input.read()
                if (char < 0) break
                append(char.toChar())
            }
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            byte.toUByte().toString(radix = 16).padStart(2, '0')
        }

    private class FirstReadLimitedSource(
        bytes: ByteArray,
        private val firstReadSize: Int,
    ) : Source {
        private val source = Buffer().write(bytes)
        private var firstRead = true

        override fun read(sink: Buffer, byteCount: Long): Long {
            val limit = if (firstRead) {
                firstRead = false
                minOf(byteCount, firstReadSize.toLong())
            } else {
                byteCount
            }
            return source.read(sink, limit)
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }
}
