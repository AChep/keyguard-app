package com.artemchep.keyguard.ipctestclient.ipc

private const val HEX_DIGITS = "0123456789abcdef"
private const val HEX_RADIX = 16
private const val HEX_SHIFT = 4
private const val HEX_MASK = 0x0F
private const val BYTE_MASK = 0xFF
private const val KEY_ID_HEX_LENGTH = 16
private const val DEFAULT_PREVIEW_BYTES = 2048
private const val TEXT_SAMPLE_BYTES = 512
private const val ASCII_PRINTABLE_MIN = 0x20
private const val ASCII_DELETE = 0x7F
private const val ASCII_TAB = 0x09
private const val ASCII_LF = 0x0A
private const val ASCII_CR = 0x0D
private const val TEXT_RATIO_PERCENT = 85
private const val PERCENT = 100

private val NO_BYTES = ByteArray(0)

/** The `ByteArray` counterpart of the stdlib's `orEmpty` overloads. */
fun ByteArray?.orEmptyBytes(): ByteArray = this ?: NO_BYTES

fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    forEach { byte ->
        val value = byte.toInt() and BYTE_MASK
        out.append(HEX_DIGITS[value ushr HEX_SHIFT])
        out.append(HEX_DIGITS[value and HEX_MASK])
    }
    return out.toString()
}

/** Renders a key ID the way OpenPGP tooling does: `0x` plus 16 hex digits. */
fun Long.toKeyIdHex(): String =
    "0x" + toULong().toString(HEX_RADIX).padStart(KEY_ID_HEX_LENGTH, '0')

/**
 * Parses a key ID typed by a human. Accepts `0x`-prefixed hex, plain decimal
 * (including the negative form a signed 64-bit key ID takes), and bare hex.
 */
fun String.toKeyIdOrNull(): Long? {
    val text = trim().replace("_", "")
    val hex = text.removePrefix("0x").removePrefix("0X")
    return when {
        text.isEmpty() -> null
        hex.length != text.length -> hex.toULongOrNull(HEX_RADIX)?.toLong()
        else -> text.toLongOrNull()
            ?: text.toULongOrNull()?.toLong()
            ?: text.toULongOrNull(HEX_RADIX)?.toLong()
    }
}

fun String.toKeyIdList(): List<Long> = split(',', '\n', ' ')
    .mapNotNull { it.toKeyIdOrNull() }

/** Heuristic used only to decide whether to show text or hex in the driver. */
fun ByteArray.looksLikeText(): Boolean {
    if (isEmpty()) return true
    val sample = take(TEXT_SAMPLE_BYTES)
    val printable = sample.count { byte ->
        val value = byte.toInt() and BYTE_MASK
        value == ASCII_TAB ||
            value == ASCII_LF ||
            value == ASCII_CR ||
            value in ASCII_PRINTABLE_MIN until ASCII_DELETE ||
            value > ASCII_DELETE
    }
    return printable * PERCENT >= sample.size * TEXT_RATIO_PERCENT
}

fun ByteArray.preview(limit: Int = DEFAULT_PREVIEW_BYTES): String {
    if (isEmpty()) return "<empty>"
    val head = copyOf(minOf(size, limit))
    val body = if (looksLikeText()) head.decodeToString() else head.toHex()
    val suffix = if (size > limit) "\n… ${size - limit} more bytes" else ""
    return body + suffix
}
