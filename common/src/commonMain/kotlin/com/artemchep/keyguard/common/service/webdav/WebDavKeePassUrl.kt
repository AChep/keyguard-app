package com.artemchep.keyguard.common.service.webdav

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodedPath

internal data class WebDavKeePassFileUrl(
    val baseUrl: String,
    val path: String,
)

internal fun String.isWebDavKeePassFileUrl(): Boolean =
    parseWebDavKeePassFileUrlOrNull(this) != null

internal fun parseWebDavKeePassFileUrlOrNull(
    url: String,
): WebDavKeePassFileUrl? = try {
    parseWebDavKeePassFileUrl(url)
} catch (_: IllegalArgumentException) {
    null
}

internal fun parseWebDavKeePassFileUrl(
    url: String,
): WebDavKeePassFileUrl {
    val parsedUrl = Url(url.trim())
    require(
        parsedUrl.protocol == URLProtocol.HTTP ||
                parsedUrl.protocol == URLProtocol.HTTPS,
    ) {
        "WebDAV KeePass database URL must use HTTP or HTTPS."
    }
    require(parsedUrl.host.isNotBlank()) {
        "WebDAV KeePass database URL must include a host."
    }
    val encodedPath = parsedUrl.encodedPath
    require(!encodedPath.endsWith('/')) {
        "WebDAV KeePass database URL must point to a file."
    }
    val lastSlash = encodedPath.lastIndexOf('/')
    require(lastSlash >= 0 && lastSlash < encodedPath.lastIndex) {
        "WebDAV KeePass database URL must include a file path."
    }
    val fileName = percentDecodeWebDavPathSegment(
        encodedPath.substring(lastSlash + 1),
    )
    require(
        '/' !in fileName &&
                fileName != "." &&
                fileName != "..",
    ) {
        "WebDAV KeePass database URL must include a valid file name."
    }
    require(fileName.endsWith(".kdbx", ignoreCase = true)) {
        "WebDAV KeePass database URL must point to a .kdbx file."
    }
    return WebDavKeePassFileUrl(
        baseUrl = URLBuilder(parsedUrl)
            .apply {
                fragment = ""
                this.encodedPath = encodedPath.substring(0, lastSlash + 1)
            }
            .buildString(),
        path = fileName,
    )
}

private fun percentDecodeWebDavPathSegment(
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
