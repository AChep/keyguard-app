package com.artemchep.keyguard.common.util

internal fun String.normalizeSha256FingerprintOrNull(): String? {
    val hex = trim()
        .replace(":", "")
        .uppercase()
    if (hex.length != 64) {
        return null
    }
    if (!hex.all { char -> char in '0'..'9' || char in 'A'..'F' }) {
        return null
    }
    return hex
        .chunked(2)
        .joinToString(":")
}
