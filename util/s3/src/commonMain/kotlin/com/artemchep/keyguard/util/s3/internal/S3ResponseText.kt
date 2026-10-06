package com.artemchep.keyguard.util.s3.internal

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.readCodePointValue

/**
 * Reads bounded UTF-8 S3 XML without saving the entire HTTP response first.
 * Keep incomplete UTF-8 sequences between chunks and count UTF-16 units,
 * matching String.length and the existing response limits.
 */
internal suspend fun HttpResponse.readS3ResponseText(maxChars: Int): String {
    val channel = bodyAsChannel()
    val pending = Buffer()
    val chunk = ByteArray(TEXT_CHUNK_SIZE)
    val text = StringBuilder()
    try {
        var eof = false
        while (text.length < maxChars && !eof) {
            val count = channel.readAvailable(chunk)
            eof = count == -1
            if (count > 0) pending.write(chunk, 0, count)
            // Four bytes suffice for any UTF-8 code point. At EOF the decoder
            // also validates the final, possibly shorter sequence.
            while (text.length < maxChars && pending.hasCodePoint(eof)) {
                val codePoint = pending.readS3CodePoint()
                if (codePoint < SUPPLEMENTARY_CODE_POINT) {
                    text.append(codePoint.toChar())
                } else {
                    val offset = codePoint - SUPPLEMENTARY_CODE_POINT
                    text.append((Char.MIN_HIGH_SURROGATE.code + (offset shr SURROGATE_BITS)).toChar())
                    text.append((Char.MIN_LOW_SURROGATE.code + (offset and SURROGATE_MASK)).toChar())
                }
            }
        }
        return text.toString().take(maxChars)
    } finally {
        channel.cancel(null)
    }
}

private fun Buffer.hasCodePoint(eof: Boolean): Boolean = size >= MAX_UTF8_BYTES || eof && size > 0L

@Suppress("MagicNumber")
private fun Buffer.readS3CodePoint(): Int {
    // The decoder substitutes U+FFFD for malformed bytes. Only accept that
    // result when the input actually encoded U+FFFD, preserving object names.
    val encodedReplacement = size >= REPLACEMENT_UTF8_BYTES &&
        this[0] == 0xEF.toByte() && this[1] == 0xBF.toByte() && this[2] == 0xBD.toByte()
    val codePoint = try {
        readCodePointValue()
    } catch (e: EOFException) {
        throw IllegalArgumentException("S3 response contains incomplete UTF-8.", e)
    }
    require(codePoint != REPLACEMENT_CODE_POINT || encodedReplacement) {
        "S3 response contains invalid UTF-8."
    }
    return codePoint
}

private const val TEXT_CHUNK_SIZE = 8192
private const val MAX_UTF8_BYTES = 4L
private const val REPLACEMENT_UTF8_BYTES = 3L
private const val REPLACEMENT_CODE_POINT = 0xFFFD
private const val SUPPLEMENTARY_CODE_POINT = 0x10000
private const val SURROGATE_BITS = 10
private const val SURROGATE_MASK = 0x3FF
