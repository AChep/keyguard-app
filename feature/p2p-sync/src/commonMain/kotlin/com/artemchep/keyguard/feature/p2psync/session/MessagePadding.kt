package com.artemchep.keyguard.feature.p2psync.session

import com.artemchep.keyguard.feature.p2psync.readBigEndian
import com.artemchep.keyguard.feature.p2psync.writeBigEndian

/**
 * Pads every message before [FrameCodec.split] cuts it into frames. The relay and the TURN
 * server see the size of each encrypted frame; padded, a message shows them only a size
 * range, not how many bytes an entry, a sync or an attachment has.
 *
 * A padded message is `u32 length | message | zeros`, big-endian, as large as the bucket
 * that holds it. The buckets are NIP-44's: 32 bytes, then steps of 32 bytes up to 256, then
 * eight steps per power of two. A message grows by less than a quarter, by about an eighth
 * on average.
 */
object MessagePadding {
    /** The size of the length prefix. */
    const val HEADER_SIZE: Int = 4

    private const val MIN_SIZE = 32L
    private const val SMALL_SIZE = 256L
    private const val STEPS_PER_POWER = 8L

    /** The size of a message of [size] bytes once padded, its length prefix included. */
    fun paddedSize(size: Int): Int {
        require(size >= 0) { "A message size can't be negative" }
        val padded = bucket(HEADER_SIZE.toLong() + size)
        require(padded <= Int.MAX_VALUE) { "The message is too large to pad" }
        return padded.toInt()
    }

    fun pad(message: ByteArray): ByteArray {
        val padded = ByteArray(paddedSize(message.size))
        padded.writeBigEndian(0, HEADER_SIZE, message.size.toLong())
        message.copyInto(padded, destinationOffset = HEADER_SIZE)
        return padded
    }

    /**
     * Returns the message in the first [length] bytes of [padded], or `null` if they are not
     * a padded message.
     */
    fun unpad(padded: ByteArray, length: Int = padded.size): ByteArray? {
        val size = padded
            .takeIf { length >= HEADER_SIZE }
            ?.readBigEndian(0, HEADER_SIZE)
            ?: return null
        // A sender pads to the bucket exactly; anything else is malformed.
        val valid = size <= length - HEADER_SIZE && bucket(HEADER_SIZE + size) == length.toLong()
        return if (valid) padded.copyOfRange(HEADER_SIZE, HEADER_SIZE + size.toInt()) else null
    }

    /** The padded size that the length prefix at the start of [padded] declares. */
    internal fun declaredPaddedSize(padded: ByteArray): Long =
        bucket(HEADER_SIZE + padded.readBigEndian(0, HEADER_SIZE))

    /** NIP-44's `calc_padded_len`: [size] rounded up to its bucket. */
    private fun bucket(size: Long): Long {
        if (size <= MIN_SIZE) return MIN_SIZE
        val nextPower = 1L shl (Long.SIZE_BITS - (size - 1).countLeadingZeroBits())
        val step = if (nextPower <= SMALL_SIZE) MIN_SIZE else nextPower / STEPS_PER_POWER
        return (size - 1) / step * step + step
    }
}
