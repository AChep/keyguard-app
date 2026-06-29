package com.artemchep.keyguard.util.foundation

fun ByteArray.requireValidRange(
    offset: Int,
    length: Int,
) {
    require(offset >= 0) {
        "Offset must not be negative."
    }
    require(length >= 0) {
        "Length must not be negative."
    }
    require(offset <= size && length <= size - offset) {
        "Offset and length are out of bounds."
    }
}
