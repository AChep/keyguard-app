package com.artemchep.keyguard.common.util

import io.ktor.util.hex

val ByteArray.int: Int
    get() {
        require(size == 4)
        return this
            .foldIndexed(0.toUInt()) { index, y, x ->
                // We need to convert byte to unsigned byte first, otherwise
                // the sign bit will plague the rest of the integer.
                val xUInt = x.toUByte().toUInt()
                val yUIntPatch = xUInt shl (size - index - 1).times(8)
                y or yUIntPatch
            }
            .toInt()
    }

fun ByteArray.toHex(): String = hex(this)

fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) {
        "Hex string must contain an even number of characters."
    }
    return ByteArray(length / 2) { index ->
        val startIndex = index * 2
        val high = hexDigitToInt(this[startIndex])
        val low = hexDigitToInt(this[startIndex + 1])
        ((high shl 4) or low).toByte()
    }
}

private fun hexDigitToInt(char: Char): Int = when (char) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    in 'A'..'F' -> char - 'A' + 10
    else -> throw IllegalArgumentException("Invalid hex character: $char")
}
