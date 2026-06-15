package com.artemchep.keyguard.common.service.file

import com.artemchep.keyguard.platform.LocalPath

fun String.toLocalPathFromFileUriOrNull(): LocalPath? = runCatching {
    val schemeSeparatorIndex = indexOf(':')
    require(schemeSeparatorIndex >= 0)
    val scheme = substring(0, schemeSeparatorIndex)
    require(scheme == "file")

    val rawPath = substring(schemeSeparatorIndex + 1)
        .substringBefore('#')
        .substringBefore('?')
    val decodedPath = decodePercentEncoded(rawPath)
        .normalizeFileUriPath()
    LocalPath(decodedPath)
}.getOrNull()

private fun String.normalizeFileUriPath(): String =
    when {
        // The path portion after stripping the "file:" scheme
        // can have the forms:
        //   "///path"         -> empty authority, standard form
        //   "//hostname/path" -> with authority (rare for local files)
        //   "/path"           -> no authority
        // In all cases we want just the absolute path starting
        // with the first '/' after the authority.
        startsWith("//") -> {
            val pathStart = indexOf('/', startIndex = 2)
            if (pathStart >= 0) substring(pathStart) else this
        }

        else -> {
            this
        }
    }

private fun decodePercentEncoded(value: String): String {
    val builder = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char != '%') {
            builder.append(char)
            index += 1
            continue
        }

        val bytes = mutableListOf<Byte>()
        while (index < value.length && value[index] == '%') {
            require(index + 2 < value.length)
            val high = value[index + 1].hexValue()
            val low = value[index + 2].hexValue()
            bytes += ((high shl 4) or low).toByte()
            index += 3
        }
        builder.append(bytes.toByteArray().decodeToString())
    }
    return builder.toString()
}

private fun Char.hexValue(): Int =
    digitToIntOrNull(radix = 16)
        ?: error("Expected a hex digit: $this")
