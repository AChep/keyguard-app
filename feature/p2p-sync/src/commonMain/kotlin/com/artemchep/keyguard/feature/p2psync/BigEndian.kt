package com.artemchep.keyguard.feature.p2psync

/** The bits in a byte. */
internal const val BYTE_BITS = 8

/** Masks a byte widened to an int or a long down to its unsigned value. */
internal const val BYTE_MASK = 0xFF

/** Returns the byte at [index] as an unsigned value. */
internal fun ByteArray.unsignedAt(index: Int): Int = this[index].toInt() and BYTE_MASK

/** Reads a big-endian unsigned number of [size] bytes from [index]. */
fun ByteArray.readBigEndian(index: Int, size: Int): Long =
    (index until index + size).fold(0L) { acc, i -> (acc shl BYTE_BITS) or unsignedAt(i).toLong() }

/** Writes the low [size] bytes of [value] big-endian at [index]. */
fun ByteArray.writeBigEndian(index: Int, size: Int, value: Long) {
    for (i in 0 until size) {
        this[index + i] = (value ushr (BYTE_BITS * (size - 1 - i))).toByte()
    }
}

/** Orders byte arrays as unsigned numbers, byte by byte; a prefix sorts first. */
fun ByteArray.compareUnsigned(other: ByteArray): Int {
    val common = minOf(size, other.size)
    val index = (0 until common).firstOrNull { this[it] != other[it] }
    return if (index != null) unsignedAt(index) - other.unsignedAt(index) else size - other.size
}
