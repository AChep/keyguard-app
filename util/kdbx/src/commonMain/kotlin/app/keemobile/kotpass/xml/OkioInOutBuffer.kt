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
        copyBuilder = StringBuilder()
        copyState = InOutBuffer.State.ACTIVE
    }

    override fun flushCopySequence() = Unit

    override fun pauseCopySequence() {
        check(copyState == InOutBuffer.State.ACTIVE) { "Copy sequence is not active" }
        copyState = InOutBuffer.State.PAUSED
    }

    override fun resumeCopySequence() {
        check(copyState == InOutBuffer.State.PAUSED) { "Copy sequence is not paused" }
        copyState = InOutBuffer.State.ACTIVE
    }

    override fun finalizeCopySequence(): CharSequence {
        check(copyState != InOutBuffer.State.INACTIVE) { "No copy sequence started" }
        val result = copyBuilder?.toString().orEmpty()
        copyBuilder = null
        copyState = InOutBuffer.State.INACTIVE
        return result
    }

    override fun addToCopySequence(char: Char) {
        check(copyState != InOutBuffer.State.INACTIVE) { "Copy sequence is not active" }
        copyBuilder!!.append(char)
    }

    override fun readSubRange(start: Int, end: Int): CharSequence {
        require(start >= inputBase && end >= start)
        ensureAvailable(end - 1)
        val actualEnd = minOf(end, inputBase + inputLength)
        return input.concatToString(start - inputBase, actualEnd - inputBase)
    }

    override fun peek(offset: Int): Int {
        val index = currentOffset + offset
        ensureAvailable(index)
        return when (val char = charAt(index) ?: return -1) {
            '\r', '\u0085', '\u2028' -> '\n'.code
            else -> char.code
        }
    }

    override fun skip(count: Int) {
        repeat(count) {
            val char = charAt(currentOffset).also {
                if (it == null) error("End of file while skipping")
            }!!
            appendCopied(char)
            currentOffset++
        }
        compact()
    }

    override fun read(): Int {
        val char = charAt(currentOffset) ?: return -1
        return when (char) {
            '\r' -> {
                val hasSecond = charAt(currentOffset + 1) == '\n' ||
                    charAt(currentOffset + 1) == '\u0085'
                appendCopied('\n')
                currentOffset += if (hasSecond) 2 else 1
                lineBreak()
                '\n'.code
            }
            '\u0085', '\u2028' -> {
                appendCopied('\n')
                currentOffset++
                lineBreak()
                '\n'.code
            }
            '\n' -> {
                appendCopied(char)
                currentOffset++
                lineBreak()
                char.code
            }
            else -> {
                appendCopied(char)
                currentOffset++
                compact()
                char.code
            }
        }
    }

    override fun readToCopyBuffer() {
        check(read() >= 0) { "End of stream while adding character to copy buffer" }
    }

    private fun lineBreak() {
        lastColumnStart = currentOffset
        currentLine++
        compact()
    }

    private fun appendCopied(char: Char) {
        if (copyState == InOutBuffer.State.ACTIVE) copyBuilder!!.append(char)
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
        if (currentOffset - inputBase < 16_384) return
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
        const val MAX_UTF8_BYTES = 4
    }
}
