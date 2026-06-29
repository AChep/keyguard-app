package org.redundent.kotlin.xml

internal fun escapeValue(
    value: Any?,
    xmlVersion: XmlVersion,
    useCharacterReference: Boolean = false
): String? {
	val asString = value?.toString() ?: return null

	if (useCharacterReference) {
		return referenceCharacter(asString)
	}

	return when (xmlVersion) {
		XmlVersion.V10 -> escapeXml10(asString)
		XmlVersion.V11 -> escapeXml11(asString)
	}
}

private fun escapeXml10(value: String): String = escapeXml(value) { code ->
    code == 0x9 ||
        code == 0xA ||
        code == 0xD ||
        code in 0x20..0xD7FF ||
        code in 0xE000..0xFFFD ||
        code in 0x10000..0x10FFFF
}

private fun escapeXml11(value: String): String = escapeXml(value) { code ->
    when (code) {
        0x0 -> false
        in 0x1..0x8,
        0xB,
        0xC,
        in 0xE..0x1F,
        in 0x7F..0x84,
        in 0x86..0x9F -> append("&#$code;").let { false }
        else -> code in 0x1..0xD7FF ||
            code in 0xE000..0xFFFD ||
            code in 0x10000..0x10FFFF
    }
}

private inline fun escapeXml(
    value: String,
    isAllowed: StringBuilder.(Int) -> Boolean,
): String = buildString(value.length) {
    var index = 0
    while (index < value.length) {
        val code = value.codePointAt(index)
        val charCount = code.charCount()
        when (code) {
            '\''.code -> append("&apos;")
            '&'.code -> append("&amp;")
            '<'.code -> append("&lt;")
            '>'.code -> append("&gt;")
            '"'.code -> append("&quot;")
            else -> {
                if (isAllowed(code)) {
                    appendCodePoint(code)
                }
            }
        }
        index += charCount
    }
}

private fun String.codePointAt(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) {
            return ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000
        }
    }
    return high.code
}

private fun Int.charCount(): Int = if (this >= 0x10000) 2 else 1

private fun StringBuilder.appendCodePoint(code: Int) {
    if (code < 0x10000) {
        append(code.toChar())
    } else {
        val offset = code - 0x10000
        append(((offset ushr 10) + 0xD800).toChar())
        append(((offset and 0x3FF) + 0xDC00).toChar())
    }
}

internal fun referenceCharacter(asString: String): String {
	val builder = StringBuilder()

	asString.toCharArray().forEach { character ->
		when (character) {
			'\'' -> builder.append("&#39;")
			'&' -> builder.append("&#38;")
			'<' -> builder.append("&#60;")
			'>' -> builder.append("&#62;")
			'"' -> builder.append("&#34;")
			else -> builder.append(character)
		}
	}

	return builder.toString()
}

internal fun buildName(name: String, namespace: Namespace?): String {
    return if (namespace == null || namespace.isDefault) {
        name
    } else {
        "${namespace.name}:$name"
    }
}

fun unsafe(value: Any?): Unsafe = Unsafe(value)
