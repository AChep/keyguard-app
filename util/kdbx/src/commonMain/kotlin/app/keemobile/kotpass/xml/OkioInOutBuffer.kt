package app.keemobile.kotpass.xml

import app.keemobile.kotpass.errors.FormatError
import nl.adaptivity.xmlutil.ExperimentalXmlUtilApi
import nl.adaptivity.xmlutil.XmlUtilInternal
import nl.adaptivity.xmlutil.core.InOutBuffer
import okio.BufferedSource

/** Lazily decodes UTF-8 from Okio for xmlutil's multiplatform parser. */
@OptIn(ExperimentalXmlUtilApi::class, XmlUtilInternal::class)
internal class OkioInOutBuffer(
    private val source: BufferedSource,
    private val maxBytes: Long,
) : InOutBuffer {
    private var input = CharArray(INPUT_CHUNK_SIZE * 2)
    private var inputLength = 0
    private val encodedInput = ByteArray(INPUT_CHUNK_SIZE + MAX_UTF8_BYTES - 1)
    private var encodedStart = 0
    private var encodedEnd = 0
    private var inputBase = 0
    private var eof = false
    private var upstreamEof = false
    private var bytesRead = 0L
    private var currentOffset = 0
    private var currentLine = 1
    private var lastColumnStart = 0
    private var copyState = InOutBuffer.State.INACTIVE
    private var copyStartOffset = 0
    private var copyBuilder: StringBuilder? = null

    init {
        if (charAt(0) == '\uFEFF') currentOffset = 1
    }

    override val offset: Int
        get() = currentOffset
    override val line: Int
        get() = currentLine
    override val column: Int
        get() = currentOffset - lastColumnStart + 1
    override val copySequenceState: InOutBuffer.State
        get() = copyState

    override fun startCopySequence() {
        check(copyState == InOutBuffer.State.INACTIVE) { "Copy sequence already started" }
        copyStartOffset = currentOffset
        copyState = InOutBuffer.State.ACTIVE
    }

    override fun flushCopySequence() {
        flushActiveCopySegment()
    }

    override fun pauseCopySequence() {
        check(copyState == InOutBuffer.State.ACTIVE) { "Copy sequence is not active" }
        flushActiveCopySegment()
        copyState = InOutBuffer.State.PAUSED
    }

    override fun resumeCopySequence() {
        check(copyState == InOutBuffer.State.PAUSED) { "Copy sequence is not paused" }
        copyStartOffset = currentOffset
        copyState = InOutBuffer.State.ACTIVE
    }

    override fun finalizeCopySequence(): CharSequence {
        check(copyState != InOutBuffer.State.INACTIVE) { "No copy sequence started" }
        val builder = copyBuilder
        val result = when {
            builder == null && copyState == InOutBuffer.State.ACTIVE ->
                inputRangeToString(copyStartOffset, currentOffset)
            builder == null -> ""
            copyState == InOutBuffer.State.ACTIVE -> {
                appendInputRange(builder, copyStartOffset, currentOffset)
                builder.toString()
            }
            else -> builder.toString()
        }
        copyBuilder = null
        copyState = InOutBuffer.State.INACTIVE
        return result
    }

    override fun addToCopySequence(char: Char) {
        check(copyState != InOutBuffer.State.INACTIVE) { "Copy sequence is not active" }
        ensureCopyBuilder().append(char)
    }

    override fun addToCopySequence(seq: CharSequence) {
        check(copyState != InOutBuffer.State.INACTIVE) { "Copy sequence is not active" }
        ensureCopyBuilder(seq.length).append(seq)
    }

    override fun readSubRange(start: Int, end: Int): CharSequence {
        require(start >= inputBase && end >= start)
        ensureAvailable(end - 1)
        val actualEnd = minOf(end, inputBase + inputLength)
        return input.concatToString(start - inputBase, actualEnd - inputBase)
    }

    override fun peek(): Int {
        var local = currentOffset - inputBase
        if (local !in 0 until inputLength) {
            ensureAvailable(currentOffset)
            local = currentOffset - inputBase
            if (local !in 0 until inputLength) return -1
        }
        return when (val char = input[local]) {
            '\r', '\u0085', '\u2028' -> '\n'.code
            else -> char.code
        }
    }

    override fun peek(offset: Int): Int {
        val index = currentOffset + offset
        var local = index - inputBase
        if (local !in 0 until inputLength) {
            ensureAvailable(index)
            local = index - inputBase
            if (local !in 0 until inputLength) return -1
        }
        return when (val char = input[local]) {
            '\r', '\u0085', '\u2028' -> '\n'.code
            else -> char.code
        }
    }

    override fun skip(count: Int) {
        repeat(count) {
            if (charAt(currentOffset) == null) error("End of file while skipping")
            currentOffset++
        }
        compact()
    }

    override fun read(): Int {
        var local = currentOffset - inputBase
        if (local !in 0 until inputLength) {
            ensureAvailable(currentOffset)
            local = currentOffset - inputBase
            if (local !in 0 until inputLength) return -1
        }
        val char = input[local]
        if (char != '\r' && char != '\n' && char != '\u0085' && char != '\u2028') {
            currentOffset++
            if (currentOffset - inputBase >= COMPACT_THRESHOLD) compact()
            return char.code
        }
        return readLineBreak(char)
    }

    private fun readLineBreak(char: Char): Int =
        when (char) {
            '\r' -> {
                val next = charAt(currentOffset + 1)
                val hasSecond = next == '\n' || next == '\u0085'
                normalizedLineBreak(currentOffset + if (hasSecond) 2 else 1)
                '\n'.code
            }
            '\u0085', '\u2028' -> {
                normalizedLineBreak(currentOffset + 1)
                '\n'.code
            }
            '\n' -> {
                currentOffset++
                lineBreak()
                char.code
            }
            else -> error("Expected an XML line break")
        }

    override fun readToCopyBuffer() {
        check(read() >= 0) { "End of stream while adding character to copy buffer" }
    }

    private fun normalizedLineBreak(newOffset: Int) {
        val copying = copyState == InOutBuffer.State.ACTIVE
        if (copying) ensureCopyBuilder(1).append('\n')
        currentOffset = newOffset
        if (copying) copyStartOffset = currentOffset
        lineBreak()
    }

    private fun lineBreak() {
        lastColumnStart = currentOffset
        currentLine++
        compact()
    }

    private fun ensureCopyBuilder(sizeHint: Int = 16): StringBuilder {
        val pending = if (copyState == InOutBuffer.State.ACTIVE) {
            currentOffset - copyStartOffset
        } else {
            0
        }
        val builder = copyBuilder ?: StringBuilder(pending + sizeHint).also {
            copyBuilder = it
        }
        if (pending > 0) {
            appendInputRange(builder, copyStartOffset, currentOffset)
            copyStartOffset = currentOffset
        }
        return builder
    }

    private fun flushActiveCopySegment() {
        if (copyState != InOutBuffer.State.ACTIVE) return
        if (copyStartOffset < currentOffset) {
            val builder = copyBuilder ?: StringBuilder(currentOffset - copyStartOffset).also {
                copyBuilder = it
            }
            appendInputRange(builder, copyStartOffset, currentOffset)
        }
        copyStartOffset = currentOffset
    }

    private fun appendInputRange(builder: StringBuilder, start: Int, end: Int) {
        check(start >= inputBase && end <= inputBase + inputLength)
        var local = start - inputBase
        val localEnd = end - inputBase
        while (local < localEnd) builder.append(input[local++])
    }

    private fun inputRangeToString(start: Int, end: Int): String {
        check(start >= inputBase && end <= inputBase + inputLength)
        return input.concatToString(start - inputBase, end - inputBase)
    }

    private fun charAt(index: Int): Char? {
        ensureAvailable(index)
        val local = index - inputBase
        return if (local in 0 until inputLength) input[local] else null
    }

    private fun ensureAvailable(index: Int) {
        while (!eof && index >= inputBase + inputLength) {
            decodeNextChunk()
        }
    }

    /**
     * Decodes a bounded byte chunk instead of asking Okio for one UTF-8 code
     * point per parser read. In realistic vaults this removes millions of
     * BufferedSource calls while preserving strict UTF-8 validation and the
     * document byte limit.
     */
    private fun decodeNextChunk() {
        while (true) {
            val decodedLength = inputLength
            decodeAvailableBytes()
            if (inputLength > decodedLength) return

            if (upstreamEof) {
                if (encodedStart < encodedEnd) malformedUtf8()
                eof = true
                return
            }

            val remaining = maxBytes - bytesRead
            val byteCount = if (remaining > 0L) {
                minOf(INPUT_CHUNK_SIZE.toLong(), remaining).toInt()
            } else {
                // Read one sentinel byte to distinguish exact-limit EOF from
                // a document that exceeds the configured limit.
                1
            }
            val carryBytes = encodedEnd - encodedStart
            if (carryBytes > 0 && encodedStart > 0) {
                encodedInput.copyInto(
                    destination = encodedInput,
                    destinationOffset = 0,
                    startIndex = encodedStart,
                    endIndex = encodedEnd,
                )
            }
            encodedStart = 0
            encodedEnd = carryBytes
            val read = source.read(encodedInput, encodedEnd, byteCount)
            if (read == -1) {
                upstreamEof = true
            } else {
                encodedEnd += read
                bytesRead += read.toLong()
                if (bytesRead > maxBytes) {
                    throw FormatError.InvalidXml(
                        "XML document exceeds the $maxBytes-byte limit."
                    )
                }
            }
        }
    }

    private fun decodeAvailableBytes() {
        ensureInputCapacity(inputLength + encodedEnd - encodedStart)
        var sourceIndex = encodedStart
        var outputIndex = inputLength
        while (sourceIndex < encodedEnd) {
            // XML is overwhelmingly ASCII. Consume each contiguous run without
            // re-entering the general UTF-8 validation path for every byte.
            while (sourceIndex < encodedEnd && encodedInput[sourceIndex] >= 0) {
                input[outputIndex++] = encodedInput[sourceIndex++].toInt().toChar()
            }
            if (sourceIndex == encodedEnd) break

            val first = encodedInput[sourceIndex].toInt() and 0xFF
            val length = when (first) {
                in 0xC2..0xDF -> 2
                in 0xE0..0xEF -> 3
                in 0xF0..0xF4 -> 4
                else -> malformedUtf8()
            }
            if (encodedEnd - sourceIndex < length) {
                encodedStart = sourceIndex
                inputLength = outputIndex
                return
            }

            val second = continuationByte(sourceIndex, 1)
            val third = if (length >= 3) continuationByte(sourceIndex, 2) else 0
            val fourth = if (length == 4) continuationByte(sourceIndex, 3) else 0

            // Reject overlong encodings, UTF-16 surrogate code points and
            // values above U+10FFFF.
            if (
                first == 0xE0 && second < 0xA0 ||
                first == 0xED && second > 0x9F ||
                first == 0xF0 && second < 0x90 ||
                first == 0xF4 && second > 0x8F
            ) {
                malformedUtf8()
            }

            val codePoint = when (length) {
                2 -> ((first and 0x1F) shl 6) or (second and 0x3F)
                3 ->
                    ((first and 0x0F) shl 12) or
                        ((second and 0x3F) shl 6) or
                        (third and 0x3F)
                else ->
                    ((first and 0x07) shl 18) or
                        ((second and 0x3F) shl 12) or
                        ((third and 0x3F) shl 6) or
                        (fourth and 0x3F)
            }
            sourceIndex += length
            if (codePoint <= 0xFFFF) {
                input[outputIndex++] = codePoint.toChar()
            } else {
                val supplementary = codePoint - 0x10000
                input[outputIndex++] = ((supplementary ushr 10) + 0xD800).toChar()
                input[outputIndex++] = ((supplementary and 0x3FF) + 0xDC00).toChar()
            }
        }
        inputLength = outputIndex
        encodedStart = 0
        encodedEnd = 0
    }

    private fun continuationByte(sourceIndex: Int, offset: Int): Int {
        val byte = encodedInput[sourceIndex + offset].toInt() and 0xFF
        if (byte !in 0x80..0xBF) malformedUtf8()
        return byte
    }

    private fun malformedUtf8(): Nothing =
        throw FormatError.InvalidXml("XML document contains malformed UTF-8.")

    private fun ensureInputCapacity(required: Int) {
        if (required <= input.size) return
        var capacity = input.size
        while (capacity < required) capacity *= 2
        input = input.copyOf(capacity)
    }

    private fun compact() {
        if (currentOffset - inputBase < COMPACT_THRESHOLD) return
        flushActiveCopySegment()
        val consumed = currentOffset - inputBase
        input.copyInto(
            destination = input,
            destinationOffset = 0,
            startIndex = consumed,
            endIndex = inputLength,
        )
        inputLength -= consumed
        inputBase = currentOffset
    }

    private companion object {
        const val INPUT_CHUNK_SIZE = 16 * 1024
        const val COMPACT_THRESHOLD = 16 * 1024
        const val MAX_UTF8_BYTES = 4
    }
}
