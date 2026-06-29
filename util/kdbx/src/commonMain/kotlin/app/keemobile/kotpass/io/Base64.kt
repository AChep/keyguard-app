package app.keemobile.kotpass.io

import kotlin.io.encoding.Base64

internal fun ByteArray.encodeBase64(): String = Base64.Default.encode(this)

internal fun ByteArray.encodeBase64UrlSafe(): String = Base64.UrlSafe.encode(this)

internal fun String.decodeBase64ToArray(): ByteArray {
    val normalized = buildString(length) {
        for (char in this@decodeBase64ToArray) {
            when (char) {
                '=' -> Unit
                '\n', '\r', ' ', '\t' -> Unit
                '-' -> append('+')
                '_' -> append('/')
                else -> append(char)
            }
        }
    }
    if (normalized.length % 4 == 1) {
        throw IllegalArgumentException("Invalid last char.")
    }

    val padding = (4 - normalized.length % 4) % 4
    val padded = normalized + "=".repeat(padding)
    return Base64.Default.decode(padded)
}
