package com.artemchep.keyguard.util.xml

internal fun decodeXmlEntities(
    value: String,
): String = buildString {
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char != '&') {
            append(char)
            index += 1
            continue
        }

        val end = value.indexOf(';', startIndex = index + 1)
        require(end >= 0) { "Unterminated XML entity reference." }

        val entity = value.substring(index + 1, end)
        val decoded = when (entity) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> decodeNumericEntity(entity)
        }
        require(decoded != null) { "Invalid XML entity reference." }
        append(decoded)
        index = end + 1
    }
}

private fun decodeNumericEntity(
    entity: String,
): String? {
    val codePoint = when {
        entity.startsWith("#x") -> entity.drop(2)
            .takeIf { digits -> digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } }
            ?.toIntOrNull(radix = 16)
        entity.startsWith("#") -> entity.drop(1)
            .takeIf { digits -> digits.all { it in '0'..'9' } }
            ?.toIntOrNull()
        else -> null
    } ?: return null
    return when {
        !codePoint.isXmlCharacter() -> null
        codePoint < MIN_SUPPLEMENTARY_CODE_POINT -> codePoint.toChar().toString()
        else -> {
            val offset = codePoint - MIN_SUPPLEMENTARY_CODE_POINT
            val high = Char.MIN_HIGH_SURROGATE + (offset shr 10)
            val low = Char.MIN_LOW_SURROGATE + (offset and 0x3FF)
            charArrayOf(high, low).concatToString()
        }
    }
}

internal fun requireXmlCharacters(value: String) {
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char.isHighSurrogate()) {
            require(index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                "Invalid XML character."
            }
            index += 2
        } else {
            require(char.code.isXmlCharacter()) { "Invalid XML character." }
            index += 1
        }
    }
}

@Suppress("MagicNumber")
private fun Int.isXmlCharacter(): Boolean =
    this == 0x09 || this == 0x0A || this == 0x0D ||
        this in 0x20..0xD7FF || this in 0xE000..0xFFFD ||
        this in MIN_SUPPLEMENTARY_CODE_POINT..MAX_CODE_POINT

private const val MIN_SUPPLEMENTARY_CODE_POINT = 0x10000
private const val MAX_CODE_POINT = 0x10FFFF
