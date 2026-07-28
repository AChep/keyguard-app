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

    @Test
    fun peekNormalizesLineEndingsAcrossRefillsWithoutConsumingInput() {
        val value = "a\r\nb\u0085c\u2028\uD83D\uDE00z"
        val input = createInput(value.encodeToByteArray(), firstReadSize = 2)
        input.startCopySequence()

        assertEquals(0, input.offset)
        assertEquals(1, input.line)
        assertEquals(1, input.column)
        assertEquals('a'.code, input.peek())
        assertEquals('\n'.code, input.peek(1))
        assertEquals('\n'.code, input.peek(2))
        assertEquals('b'.code, input.peek(3))
        assertEquals('\n'.code, input.peek(4))
        assertEquals('c'.code, input.peek(5))
        assertEquals('\n'.code, input.peek(6))
        assertEquals('\uD83D'.code, input.peek(7))
        assertEquals('\uDE00'.code, input.peek(8))
        assertEquals('z'.code, input.peek(9))
        assertEquals(-1, input.peek(10))
        assertEquals(0, input.offset)
        assertEquals(1, input.line)
        assertEquals(1, input.column)
        assertEquals("", input.finalizeCopySequence())
    }

    @Test
    fun largePeekCrossesDecodeChunksAndPreservesPosition() {
        val value = "a".repeat(20_000) + "\uD83D\uDE00z"
        val input = createInput(value.encodeToByteArray(), firstReadSize = 1)

        assertEquals('\uD83D'.code, input.peek(20_000))
        assertEquals('\uDE00'.code, input.peek(20_001))
        assertEquals('z'.code, input.peek(20_002))
        assertEquals(-1, input.peek(20_003))
        assertEquals(0, input.offset)
        assertEquals(1, input.line)
        assertEquals(1, input.column)
        assertEquals('a'.code, input.read())
    }

    @Test
    fun readNormalizesSplitXmlLineEndings() {
        val cases = listOf(
            "\r\nx" to "\nx",
            "\r\u0085x" to "\nx",
            "\rx" to "\nx",
            "\u0085x" to "\nx",
            "\u2028x" to "\nx",
        )

        cases.forEach { (value, expected) ->
            val bytes = value.encodeToByteArray()
            assertEquals(
                expected,
                decode(bytes, firstReadSize = 1),
                "value=${bytes.toHex()}",
            )

            val input = createInput(bytes, firstReadSize = 1)
            input.startCopySequence()
            while (input.read() >= 0) {
                // Consume the complete value into the active copy sequence.
            }
            assertEquals(expected, input.finalizeCopySequence(), "copied value=${bytes.toHex()}")
        }
    }

    @Test
    fun readPreservesCopyAndLocationStateAcrossFastAndLineBreakPaths() {
        val input = createInput("a\r\nb".encodeToByteArray(), firstReadSize = 2)
        input.startCopySequence()

        assertEquals('a'.code, input.read())
        assertEquals(1, input.line)
        assertEquals(2, input.column)
        assertEquals('\n'.code, input.read())
        assertEquals(2, input.line)
        assertEquals(1, input.column)
        assertEquals('b'.code, input.read())
        assertEquals(2, input.line)
        assertEquals(2, input.column)
        assertEquals(-1, input.read())
        assertEquals("a\nb", input.finalizeCopySequence())
    }

    @Test
    fun copySequenceCapturesContiguousTextAcrossCompaction() {
        val value = "a".repeat(20_000) + " — \uD83D\uDE00"
        val input = createInput(value.encodeToByteArray(), firstReadSize = 1)
        input.startCopySequence()

        while (input.read() >= 0) {
            // Consume the complete value so compaction occurs while copying.
        }

        assertEquals(value, input.finalizeCopySequence())
    }

    @Test
    fun copySequencePreservesOrderingAcrossFlushPauseAndResume() {
        val input = createInput("ab--cd".encodeToByteArray(), firstReadSize = 2)
        input.startCopySequence()
        input.skip(1)
        input.flushCopySequence()
        input.skip(1)
        input.pauseCopySequence()
        input.skip(2)
        input.addToCopySequence('X')
        input.addToCopySequence("YZ")
        input.resumeCopySequence()
        input.skip(2)

        assertEquals("abXYZcd", input.finalizeCopySequence())
    }

    @Test
    fun emptyPausedCopySequenceCanBeFinalized() {
        val input = createInput("x".encodeToByteArray(), firstReadSize = 1)
        input.startCopySequence()
        input.pauseCopySequence()

        assertEquals("", input.finalizeCopySequence())
    }

    private fun decode(
        bytes: ByteArray,
        firstReadSize: Int = bytes.size.coerceAtLeast(1),
    ): String {
        val input = createInput(bytes, firstReadSize)
        return buildString {
            while (true) {
                val char = input.read()
                if (char < 0) break
                append(char.toChar())
            }
        }
    }

    private fun createInput(bytes: ByteArray, firstReadSize: Int): OkioInOutBuffer {
        val source = FirstReadLimitedSource(bytes, firstReadSize).buffer()
        return OkioInOutBuffer(source, bytes.size.toLong())
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
