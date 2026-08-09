package com.artemchep.keyguard.util.webdav

import com.artemchep.keyguard.util.webdav.internal.hrefToWebDavPath
import com.artemchep.keyguard.util.webdav.internal.normalizeBaseCollectionUrl
import com.artemchep.keyguard.util.webdav.internal.resolveWebDavUrl
import com.artemchep.keyguard.util.webdav.internal.validatePrefixPath

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
