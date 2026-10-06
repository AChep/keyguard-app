package com.artemchep.keyguard.util.dns.internal

import com.artemchep.keyguard.util.dns.DnsFormatException
import com.artemchep.keyguard.util.dns.DnsResponseCodeException
import com.artemchep.keyguard.util.dns.DnsTruncatedException

internal const val RR_TYPE_TXT = 16
internal const val RR_CLASS_IN = 1

private const val HEADER_SIZE = 12
private const val MAX_MESSAGE_SIZE = 65535
private const val ID_SIZE = 2
private const val TRAILING_COUNTS_SIZE = 4
private const val FLAG_QR = 0x8000
private const val OPCODE_MASK = 0x7800
private const val FLAG_TC = 0x0200
private const val RCODE_MASK = 0x000F
private const val RCODE_NO_ERROR = 0
private const val RCODE_NAME_ERROR = 3
private const val QUESTION_FIXED_SIZE = 4
private const val TTL_SIZE = 4
private const val LABEL_TYPE_MASK = 0xC0
private const val LABEL_TYPE_POINTER = 0xC0
private const val LABEL_TYPE_PLAIN = 0x00
private const val POINTER_HIGH_MASK = 0x3F
private const val MAX_POINTER_JUMPS = 64
private const val MAX_NAME_WIRE_LENGTH = 255
private const val MAX_TXT_RECORDS = 64
private const val BYTE_MASK = 0xFF
private const val BYTE_BITS = 8

/**
 * Parses the parts of a DNS message that a TXT lookup needs: the header, the
 * answer section and TXT RDATA. See RFC 1035 §4.1.
 */
internal object DnsMessageParser {
    /** Parses a complete response message and returns the TXT records in it. */
    fun parseTxtMessage(message: ByteArray): List<String> {
        requireFormat(message.size in HEADER_SIZE..MAX_MESSAGE_SIZE) {
            "DNS message has an invalid size."
        }
        val reader = DnsReader(message)
        reader.skip(ID_SIZE)
        val flags = reader.readU16()
        val questionCount = reader.readU16()
        val answerCount = reader.readU16()
        reader.skip(TRAILING_COUNTS_SIZE)
        if (!hasAnswers(flags)) {
            return emptyList()
        }

        repeat(questionCount) {
            reader.skipName()
            reader.skip(QUESTION_FIXED_SIZE)
        }
        val records = mutableListOf<String>()
        repeat(answerCount) {
            reader.skipName()
            val type = reader.readU16()
            val rrClass = reader.readU16()
            reader.skip(TTL_SIZE)
            val rdata = reader.readBytes(reader.readU16())
            if (type == RR_TYPE_TXT && rrClass == RR_CLASS_IN && records.size < MAX_TXT_RECORDS) {
                records += parseTxtRdata(rdata)
            }
        }
        return records
    }

    /** Concatenates the character-strings of one TXT record's RDATA. */
    fun parseTxtRdata(rdata: ByteArray): String {
        val reader = DnsReader(rdata)
        val buffer = ByteArray(rdata.size)
        var length = 0
        while (reader.remaining > 0) {
            val chunk = reader.readBytes(reader.readU8())
            chunk.copyInto(buffer, destinationOffset = length)
            length += chunk.size
        }
        return buffer.decodeToString(endIndex = length)
    }

    /**
     * Returns `false` for a clean "no such name" response and `true` when the
     * answer section should be read. Throws on every other condition.
     */
    private fun hasAnswers(flags: Int): Boolean {
        requireFormat(flags and FLAG_QR != 0) { "DNS message is not a response." }
        requireFormat(flags and OPCODE_MASK == 0) { "DNS response has an unexpected opcode." }
        if (flags and FLAG_TC != 0) {
            throw DnsTruncatedException()
        }
        val rcode = flags and RCODE_MASK
        if (rcode != RCODE_NO_ERROR && rcode != RCODE_NAME_ERROR) {
            throw DnsResponseCodeException(rcode)
        }
        return rcode == RCODE_NO_ERROR
    }
}

private class DnsReader(
    private val data: ByteArray,
) {
    private var position: Int = 0

    val remaining: Int
        get() = data.size - position

    fun readU8(): Int {
        val value = byteAt(position)
        position += 1
        return value
    }

    fun readU16(): Int = (readU8() shl BYTE_BITS) or readU8()

    fun skip(count: Int) {
        requireFormat(count <= remaining) { "DNS message ends unexpectedly." }
        position += count
    }

    fun readBytes(count: Int): ByteArray {
        requireFormat(count <= remaining) { "DNS message ends unexpectedly." }
        val bytes = data.copyOfRange(position, position + count)
        position += count
        return bytes
    }

    /** Skips a possibly compressed domain name, see RFC 1035 §4.1.4. */
    fun skipName() {
        var cursor = position
        var end = -1
        var jumps = 0
        var wireLength = 0
        while (true) {
            val length = byteAt(cursor)
            if (length == 0) {
                if (end < 0) end = cursor + 1
                break
            }
            when (length and LABEL_TYPE_MASK) {
                LABEL_TYPE_POINTER -> {
                    val target = ((length and POINTER_HIGH_MASK) shl BYTE_BITS) or byteAt(cursor + 1)
                    requireFormat(target < cursor) { "DNS name pointer does not point backwards." }
                    jumps += 1
                    requireFormat(jumps <= MAX_POINTER_JUMPS) { "DNS name has too many pointers." }
                    if (end < 0) end = cursor + 2
                    cursor = target
                }

                LABEL_TYPE_PLAIN -> {
                    wireLength += length + 1
                    requireFormat(wireLength <= MAX_NAME_WIRE_LENGTH) { "DNS name is too long." }
                    cursor += length + 1
                }

                else -> throw DnsFormatException("DNS name uses an unsupported label type.")
            }
        }
        position = end
    }

    private fun byteAt(index: Int): Int {
        requireFormat(index < data.size) { "DNS message ends unexpectedly." }
        return data[index].toInt() and BYTE_MASK
    }
}

private inline fun requireFormat(
    condition: Boolean,
    message: () -> String,
) {
    if (!condition) {
        throw DnsFormatException(message())
    }
}
