package com.artemchep.keyguard.util.webdav.internal

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.encodedPath

internal fun normalizeBaseCollectionUrl(
    value: String,
): Url {
    require(value.isNotBlank()) {
        "WebDAV base URL must not be blank."
    }
    val normalized = if (value.endsWith("/")) value else "$value/"
    return Url(normalized)
}

internal fun validateObjectPath(
    value: String,
): String {
    require(!value.startsWith("/")) {
        "WebDAV object path must be relative."
    }
    val path = value.trimEnd('/')
    require(path.isNotBlank()) {
        "WebDAV object path must not be blank."
    }
    require(path.isRelativeWebDavPath()) {
        "WebDAV object path must be relative and must not contain empty, current, or parent segments."
    }
    return path
}

internal fun validatePrefixPath(
    value: String,
): String {
    require(!value.startsWith("/")) {
        "WebDAV prefix path must be relative."
    }
    if (value.isEmpty()) {
        return value
    }
    val checkedParts = if (value.endsWith("/")) {
        value.split('/').dropLast(1)
    } else {
        value.split('/')
    }
    require(checkedParts.none { it.isEmpty() || it == "." || it == ".." }) {
        "WebDAV prefix path must not contain empty, current, or parent segments."
    }
    return value
}

internal fun resolveWebDavUrl(
    baseUrl: Url,
    path: String,
    collection: Boolean = false,
): String {
    val parts = path
        .trim('/')
        .split('/')
        .filter { it.isNotEmpty() }
    val url = URLBuilder(baseUrl).apply {
        if (parts.isNotEmpty()) {
            appendPathSegments(parts)
        }
        if (collection && !encodedPath.endsWith("/")) {
            encodedPath += "/"
        }
    }
    return url.buildString()
}

internal fun hrefToWebDavPath(
    baseUrl: Url,
    href: String,
): String? {
    val encodedHrefPath = href
        .trim()
        .takeIf { it.isNotEmpty() }
        ?.extractEncodedPath()
        ?: return null
    val basePath = baseUrl.encodedPath
        .ensureSuffix("/")
    if (!encodedHrefPath.startsWith(basePath)) {
        return null
    }

    return encodedHrefPath
        .removePrefix(basePath)
        .trimEnd('/')
        .split('/')
        .filter { it.isNotEmpty() }
        .joinToString("/") { segment ->
            percentDecode(segment)
        }
}

private fun String.extractEncodedPath(): String {
    val withoutFragment = substringBefore('#')
    val withoutQuery = withoutFragment.substringBefore('?')
    return if (withoutQuery.startsWith("http://") || withoutQuery.startsWith("https://")) {
        Url(withoutQuery).encodedPath
    } else {
        withoutQuery
    }
}

private fun String.ensureSuffix(
    suffix: String,
): String = if (endsWith(suffix)) this else this + suffix

private fun String.isRelativeWebDavPath(): Boolean =
    !startsWith("/") &&
            split('/').none { it.isEmpty() || it == "." || it == ".." }

private fun percentDecode(
    value: String,
): String {
    val bytes = mutableListOf<Byte>()
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%' && index + 2 < value.length) {
            val byte = value
                .substring(index + 1, index + 3)
                .toIntOrNull(radix = 16)
            if (byte != null) {
                bytes += byte.toByte()
                index += 3
                continue
            }
        }

        char
            .toString()
            .encodeToByteArray()
            .forEach { byte -> bytes += byte }
        index += 1
    }
    return bytes
        .toByteArray()
        .decodeToString()
}
