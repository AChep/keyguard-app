package com.artemchep.keyguard.feature.browserextension.protocol

/**
 * The plaintext framing of README section 3: each transport message is a flags
 * byte followed by data, and a message is the data of consecutive fragments, up
 * to and including the first one without [FLAG_MORE].
 */
object BrowserFragments {
    /** Bit 0 of the flags byte: more fragments of this message follow. */
    const val FLAG_MORE: Int = 0x01

    /** The largest message, 1 MiB. */
    const val MAX_MESSAGE_SIZE: Int = 1_048_576

    /** The most fragments of one message. */
    const val MAX_FRAGMENTS: Int = 32

    /** The most data of one fragment: the largest Noise plaintext, less the flags byte. */
    const val MAX_FRAGMENT_DATA: Int = 65_518

    /**
     * Splits [message], 1 to [MAX_MESSAGE_SIZE] bytes, into fragments of at
     * most [maxData] bytes of data each.
     */
    fun split(message: ByteArray, maxData: Int = MAX_FRAGMENT_DATA): List<ByteArray> {
        require(message.size in 1..MAX_MESSAGE_SIZE) { "A message is 1 to $MAX_MESSAGE_SIZE bytes" }
        require(maxData in 1..MAX_FRAGMENT_DATA) { "A fragment holds 1 to $MAX_FRAGMENT_DATA bytes" }
        val count = (message.size + maxData - 1) / maxData
        require(count <= MAX_FRAGMENTS) { "A message is at most $MAX_FRAGMENTS fragments" }
        return List(count) { index ->
            val from = index * maxData
            val to = minOf(from + maxData, message.size)
            val flags = if (index < count - 1) FLAG_MORE else 0
            ByteArray(1 + to - from).also { fragment ->
                fragment[0] = flags.toByte()
                message.copyInto(fragment, destinationOffset = 1, startIndex = from, endIndex = to)
            }
        }
    }
}

/**
 * Joins the fragments that one side receives into messages. Each violation of
 * README section 3 throws [BrowserProtocolException], after which the session
 * ends: a reserved bit, an empty fragment, an oversized message or a fragment
 * past [BrowserFragments.MAX_FRAGMENTS].
 */
class BrowserReassembler {
    /** The fragments with [BrowserFragments.FLAG_MORE] of the unfinished message. */
    private val parts = mutableListOf<ByteArray>()
    private var size = 0

    /** Takes the next [fragment]; returns the message it completes, or `null` while more follow. */
    fun add(fragment: ByteArray): ByteArray? {
        val flags = fragment.firstOrNull()?.toInt()?.and(BYTE_MASK) ?: 0
        val data = fragment.size - 1
        val violation = when {
            data < 1 -> "An empty fragment"
            flags and BrowserFragments.FLAG_MORE.inv() != 0 -> "A reserved bit is set"
            data > BrowserFragments.MAX_FRAGMENT_DATA -> "An oversized fragment"
            parts.size >= BrowserFragments.MAX_FRAGMENTS -> "Too many fragments"
            size + data > BrowserFragments.MAX_MESSAGE_SIZE -> "An oversized message"
            else -> null
        }
        if (violation != null) throw BrowserProtocolException(violation)
        if (flags and BrowserFragments.FLAG_MORE != 0) {
            parts += fragment.copyOfRange(1, fragment.size)
            size += data
            return null
        }
        val message = ByteArray(size + data)
        var offset = 0
        parts.forEach { part ->
            part.copyInto(message, destinationOffset = offset)
            offset += part.size
            part.fill(0)
        }
        // The last fragment goes straight into the message.
        fragment.copyInto(message, destinationOffset = offset, startIndex = 1)
        parts.clear()
        size = 0
        return message
    }

    /** Zeroes the fragments of an unfinished message. */
    fun clear() {
        parts.forEach { it.fill(0) }
        parts.clear()
        size = 0
    }
}

private const val BYTE_MASK = 0xff
