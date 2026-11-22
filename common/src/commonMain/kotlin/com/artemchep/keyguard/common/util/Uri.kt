package com.artemchep.keyguard.common.util

const val PROTOCOL_ANDROID_APP = "androidapp://"
const val PROTOCOL_IOS_APP = "iosapp://"

val REGEX_ANDROID_APP = "androidapp://([a-zA-Z]+[a-zA-Z0-9_]*)(\\.[a-zA-Z]+[a-zA-Z0-9_]*)*".toRegex()

internal fun parseAndroidAppPackageNameOrNull(uri: String): String? {
    val normalizedUri = uri.trim()
    if (!REGEX_ANDROID_APP.matches(normalizedUri)) {
        return null
    }
    return normalizedUri.removePrefix(PROTOCOL_ANDROID_APP)
}

fun ensureUrlScheme(url: String): String =
    if (url.isBlank() || url.contains("://")) {
        url
    } else {
        "https://$url"
    }

private val REGEX_HTTP_SCHEME_TYPO = "^https?[:/]".toRegex(RegexOption.IGNORE_CASE)

/**
 * The [url] of a server with a lowercase scheme, or `https://` if it has none.
 * A mistyped `http(s)` scheme, such as `https:/host`, stays as typed so the
 * validation rejects it instead of reading `https` as the host.
 */
fun normalizeServerUrl(url: String): String {
    val schemeEnd = url.indexOf("://")
    return when {
        schemeEnd >= 0 -> url.substring(0, schemeEnd).lowercase() + url.substring(schemeEnd)
        REGEX_HTTP_SCHEME_TYPO.containsMatchIn(url) -> url
        else -> ensureUrlScheme(url)
    }
}
