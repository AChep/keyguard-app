package com.artemchep.keyguard.util.foundation.crypto

internal fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff)
        .toString(16)
        .padStart(2, '0')
}

internal fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) {
        "Hex string must have an even length."
    }
    return ByteArray(length / 2) { index ->
        val hi = this[index * 2].digitToInt(16)
        val lo = this[index * 2 + 1].digitToInt(16)
        ((hi shl 4) or lo).toByte()
    }
}
