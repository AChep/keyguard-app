package com.artemchep.keyguard.util.messagepack

/**
 * Walks the headers of one MessagePack value without decoding it. See
 * [MessagePackCodec.isWellFormed].
 *
 * Iterative, so a deep input cannot overflow the stack here either.
 */
// The numbers are the type bytes of the MessagePack format.
@Suppress("MagicNumber")
internal class MessagePackScanner(
    private val bytes: ByteArray,
    private val maxDepth: Int,
) {
    private var position = 0

    private val remaining: Long get() = (bytes.size - position).toLong()

    fun scan(): Boolean {
        // The values still to read at each open level; level 0 holds the root.
        val pending = LongArray(maxDepth + 1)
        pending[0] = 1
        var depth = 0
        var valid = true
        while (valid && depth >= 0) {
            if (pending[depth] == 0L) {
                depth -= 1
                continue
            }
            pending[depth] -= 1
            val children = readHeader()
            when {
                children == null -> valid = false
                children == 0L -> Unit
                // Every value takes at least one byte, so a count past the
                // end of the input is a lie.
                depth == maxDepth || children > remaining -> valid = false
                else -> {
                    depth += 1
                    pending[depth] = children
                }
            }
        }
        return valid && position == bytes.size
    }

    /**
     * Reads one header and skips its payload. Returns the number of values the
     * header's container holds, `0` for a scalar, or `null` if the input ends
     * early or holds the never-used type `0xc1`.
     */
    // The cases are the format's type table.
    @Suppress("CyclomaticComplexMethod")
    private fun readHeader(): Long? {
        val type = readUnsigned(Byte.SIZE_BYTES)?.toInt()
            ?: return null
        return when (type) {
            // fixint, negative fixint, nil, false, true
            in 0x00..0x7f, in 0xe0..0xff, 0xc0, 0xc2, 0xc3 -> 0L
            // fixmap, fixarray
            in 0x80..0x8f -> (type and 0x0f) * 2L
            in 0x90..0x9f -> (type and 0x0f).toLong()
            // fixstr
            in 0xa0..0xbf -> skip((type and 0x1f).toLong())
            // bin and str 8/16/32
            0xc4, 0xd9 -> skip(readUnsigned(Byte.SIZE_BYTES))
            0xc5, 0xda -> skip(readUnsigned(Short.SIZE_BYTES))
            0xc6, 0xdb -> skip(readUnsigned(Int.SIZE_BYTES))
            // ext 8/16/32: a length, then a type byte and the data
            0xc7 -> skip(readUnsigned(Byte.SIZE_BYTES)?.plus(EXT_TYPE_BYTES))
            0xc8 -> skip(readUnsigned(Short.SIZE_BYTES)?.plus(EXT_TYPE_BYTES))
            0xc9 -> skip(readUnsigned(Int.SIZE_BYTES)?.plus(EXT_TYPE_BYTES))
            // float, uint and int of each width
            0xcc, 0xd0 -> skip(Byte.SIZE_BYTES.toLong())
            0xcd, 0xd1 -> skip(Short.SIZE_BYTES.toLong())
            0xca, 0xce, 0xd2 -> skip(Int.SIZE_BYTES.toLong())
            0xcb, 0xcf, 0xd3 -> skip(Long.SIZE_BYTES.toLong())
            // fixext 1/2/4/8/16: a type byte and the data
            0xd4 -> skip(EXT_TYPE_BYTES + Byte.SIZE_BYTES)
            0xd5 -> skip(EXT_TYPE_BYTES + Short.SIZE_BYTES)
            0xd6 -> skip(EXT_TYPE_BYTES + Int.SIZE_BYTES)
            0xd7 -> skip(EXT_TYPE_BYTES + Long.SIZE_BYTES)
            0xd8 -> skip(EXT_TYPE_BYTES + FIXEXT16_DATA_BYTES)
            // array and map 16/32
            0xdc -> readUnsigned(Short.SIZE_BYTES)
            0xdd -> readUnsigned(Int.SIZE_BYTES)
            0xde -> readUnsigned(Short.SIZE_BYTES)?.times(2)
            0xdf -> readUnsigned(Int.SIZE_BYTES)?.times(2)
            else -> null
        }
    }

    /** Skips [count] bytes and returns `0`, or `null` past the end. */
    private fun skip(count: Long?): Long? {
        if (count == null || count > remaining) {
            return null
        }
        position += count.toInt()
        return 0L
    }

    /** A big-endian unsigned integer of [size] bytes, or `null` past the end. */
    private fun readUnsigned(size: Int): Long? {
        if (size > remaining) {
            return null
        }
        var value = 0L
        repeat(size) {
            value = (value shl Byte.SIZE_BITS) or (bytes[position++].toLong() and 0xff)
        }
        return value
    }

    private companion object {
        const val EXT_TYPE_BYTES = 1L
        const val FIXEXT16_DATA_BYTES = 0x10
    }
}
