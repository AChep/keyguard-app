package com.artemchep.keyguard.util.webdav

import com.artemchep.keyguard.util.webdav.internal.hrefToWebDavPath
import com.artemchep.keyguard.util.webdav.internal.normalizeBaseCollectionUrl
import com.artemchep.keyguard.util.webdav.internal.resolveWebDavUrl
import com.artemchep.keyguard.util.webdav.internal.validatePrefixPath
import io.ktor.http.Url

/**
 * Resolves a relative WebDAV path against a collection URL while encoding
 * every path segment independently.
 */
fun resolveWebDavResourceUrl(
    baseUrl: String,
    path: String,
    collection: Boolean = false,
): String {
    val normalizedPath = validatePrefixPath(path).trimEnd('/')
    require(collection || normalizedPath.isNotEmpty()) {
        "WebDAV file path must not be blank."
    }
    return resolveWebDavUrl(
        baseUrl = normalizeBaseCollectionUrl(baseUrl),
        path = normalizedPath,
        collection = collection,
    )
}

/**
 * Normalizes a relative WebDAV path: strips the surrounding slashes and
 * rejects paths that contain empty, current, or parent segments.
 */
fun normalizeWebDavRelativePath(
    path: String,
): String = validatePrefixPath(path.trim('/'))

/**
 * Returns the decoded relative path when [resourceUrl] is inside [baseUrl].
 */
fun webDavRelativePathOrNull(
    baseUrl: String,
    resourceUrl: String,
): String? = hrefToWebDavPath(
    baseUrl = normalizeBaseCollectionUrl(baseUrl),
    href = resourceUrl,
)

/**
 * Returns `true` for an absolute http(s) collection URL with a host, no inline
 * credentials, no fragment, and no raw whitespace or broken percent escapes.
 */
fun isValidWebDavCollectionUrl(
    value: String,
): Boolean = runCatching {
    val text = value.trim()
    require(!text.any(Char::isWhitespace) && '#' !in text)
    require(!invalidPercentEscapeRegex.containsMatchIn(text))
    require(
        text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true),
    )
    val authority = text.substringAfter("://").substringBefore('/').substringBefore('?')
    require('@' !in authority && authorityRegex.matches(authority))
    Url(text).host.isNotBlank()
}.getOrDefault(false)

private val invalidPercentEscapeRegex = Regex("%(?![0-9a-fA-F]{2})")

private val authorityRegex = Regex("(?:\\[[^\\[\\]]+\\]|[^:\\[\\]]+)(?::[0-9]+)?")
