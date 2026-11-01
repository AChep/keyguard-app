package com.artemchep.keyguard.util.io

private val BOM_UTF8 = intArrayOf(0xEF, 0xBB, 0xBF)
private val BOM_UTF16_LE = intArrayOf(0xFF, 0xFE)
private val BOM_UTF16_BE = intArrayOf(0xFE, 0xFF)

private const val BYTE_MASK = 0xFF
private const val BITS_PER_BYTE = 8
private val WINDOWS_1252_HIGH_RANGE = 0x80..0x9F

/**
 * Decodes text of an unknown encoding: UTF-8 or UTF-16 after a byte-order
 * mark, which is dropped; without one, UTF-8 if the bytes are valid UTF-8,
 * Windows-1252 otherwise. Covers what a file that passed through a
 * spreadsheet application may come back as.
 */
fun decodeTextDetectingEncoding(
    bytes: ByteArray,
): String = when {
    bytes.startsWith(BOM_UTF8) ->
        bytes.decodeToString(startIndex = BOM_UTF8.size)

    bytes.startsWith(BOM_UTF16_LE) ->
        decodeUtf16(bytes, offset = BOM_UTF16_LE.size, littleEndian = true)

    bytes.startsWith(BOM_UTF16_BE) ->
        decodeUtf16(bytes, offset = BOM_UTF16_BE.size, littleEndian = false)

    else -> try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        decodeWindows1252(bytes)
    }
}

private fun ByteArray.startsWith(prefix: IntArray): Boolean =
    size >= prefix.size && prefix.indices.all { index ->
        (this[index].toInt() and BYTE_MASK) == prefix[index]
    }

private fun decodeUtf16(
    bytes: ByteArray,
    offset: Int,
    littleEndian: Boolean,
): String {
    val out = StringBuilder((bytes.size - offset) / 2)
    var index = offset
    while (index + 1 < bytes.size) {
        val first = bytes[index].toInt() and BYTE_MASK
        val second = bytes[index + 1].toInt() and BYTE_MASK
        val code = if (littleEndian) {
            (second shl BITS_PER_BYTE) or first
        } else {
            (first shl BITS_PER_BYTE) or second
        }
        out.append(code.toChar())
        index += 2
    }
    return out.toString()
}

/**
 * Windows-1252 differs from ISO-8859-1 only in `0x80..0x9F`; the five
 * positions it leaves undefined decode to the replacement character.
 */
private val windows1252High = charArrayOf(
    '€', '�', '‚', 'ƒ', '„', '…', '†', '‡',
    'ˆ', '‰', 'Š', '‹', 'Œ', '�', 'Ž', '�',
    '�', '‘', '’', '“', '”', '•', '–', '—',
    '˜', '™', 'š', '›', 'œ', '�', 'ž', 'Ÿ',
)

private fun decodeWindows1252(
    bytes: ByteArray,
): String {
    val out = StringBuilder(bytes.size)
    bytes.forEach { byte ->
        val code = byte.toInt() and BYTE_MASK
        val char = if (code in WINDOWS_1252_HIGH_RANGE) {
            windows1252High[code - WINDOWS_1252_HIGH_RANGE.first]
        } else {
            code.toChar()
        }
        out.append(char)
    }
    return out.toString()
}
