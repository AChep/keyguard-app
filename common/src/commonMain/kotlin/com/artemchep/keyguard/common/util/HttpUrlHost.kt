package com.artemchep.keyguard.common.util

import io.ktor.http.Url

internal fun parseHttpUrlHostOrNull(
    url: String,
    removeWww: Boolean = false,
): String? {
    val authorityStart = when {
        url.startsWith("http://", ignoreCase = true) -> "http://".length
        url.startsWith("https://", ignoreCase = true) -> "https://".length
        else -> return null
    }
    val authorityEnd = url.indexOfFirstFrom(authorityStart) { char ->
        char == '/' || char == '?' || char == '#'
    }.takeIf { it >= 0 } ?: url.length

    val simpleHost = url.simpleHttpHostOrNull(
        startIndex = authorityStart,
        endIndex = authorityEnd,
        removeWww = removeWww,
    )
    if (simpleHost != null) {
        return simpleHost
    }

    val host = kotlin.runCatching {
        Url(url).host
    }.getOrNull() ?: return null
    return if (removeWww) host.removePrefix("www.") else host
}

private inline fun String.indexOfFirstFrom(
    startIndex: Int,
    predicate: (Char) -> Boolean,
): Int {
    for (index in startIndex..lastIndex) {
        if (predicate(this[index])) {
            return index
        }
    }
    return -1
}

private fun String.simpleHttpHostOrNull(
    startIndex: Int,
    endIndex: Int,
    removeWww: Boolean,
): String? {
    if (startIndex >= endIndex) {
        return null
    }
    for (index in startIndex..<endIndex) {
        val char = this[index]
        val isSimpleHostCharacter = char in 'a'..'z' ||
            char in '0'..'9' ||
            char == '.' ||
            char == '-'
        if (!isSimpleHostCharacter) {
            return null
        }
    }

    val hostStart = if (
        removeWww &&
        endIndex - startIndex > "www.".length &&
        startsWith("www.", startIndex)
    ) {
        startIndex + "www.".length
    } else {
        startIndex
    }
    return substring(hostStart, endIndex)
}
