package com.artemchep.keyguard.common.service.webdav

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
    val normalized = url.trim()
        .substringBefore('#')
        .substringBefore('?')
    require(!normalized.endsWith('/')) {
        "WebDAV KeePass database URL must point to a file."
    }
    require(normalized.startsWith("http://") || normalized.startsWith("https://")) {
        "WebDAV KeePass database URL must use HTTP or HTTPS."
    }
    val lastSlash = normalized.lastIndexOf('/')
    require(lastSlash >= "https://".length) {
        "WebDAV KeePass database URL must include a file path."
    }
    val fileName = normalized.substring(lastSlash + 1)
    require(fileName.endsWith(".kdbx", ignoreCase = true)) {
        "WebDAV KeePass database URL must point to a .kdbx file."
    }
    return WebDavKeePassFileUrl(
        baseUrl = normalized.substring(0, lastSlash + 1),
        path = percentDecodeWebDavPathSegment(fileName),
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
