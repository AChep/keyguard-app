package com.artemchep.keyguard.feature.auth.common.util

import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import io.ktor.http.URLParserException
import io.ktor.http.Url

enum class ValidationUrl {
    OK,
    ERROR_EMPTY,
    ERROR_INVALID,
}

val HTTP_URL_SCHEMES = setOf("http", "https")

private const val HOST_LABEL_MAX_LENGTH = 63

private const val HOST_FORBIDDEN_CHARS = " #%/:?@[\\]"

private const val IPV6_GROUP_COUNT = 8

private const val IPV6_GROUP_MAX_LENGTH = 4

/**
 * Checks the base [url] of a server. The endpoints are appended
 * to it, so a query or a fragment is invalid.
 */
fun validateUrl(
    url: String?,
    allowBlank: Boolean = false,
    schemes: Set<String> = HTTP_URL_SCHEMES,
): ValidationUrl {
    return if (url == null || url.isBlank()) {
        if (allowBlank) {
            ValidationUrl.OK
        } else {
            ValidationUrl.ERROR_EMPTY
        }
    } else if ('?' in url || '#' in url || !isServerUrl(url, schemes)) {
        ValidationUrl.ERROR_INVALID
    } else {
        ValidationUrl.OK
    }
}

/**
 * `true` if [url] has one of the lowercase [schemes], no whitespace
 * and a host.
 */
internal fun isServerUrl(
    url: String,
    schemes: Set<String>,
): Boolean = urlSchemeOf(url) in schemes &&
        url.none(Char::isWhitespace) &&
        // The URL parser falls back to the localhost
        // when the URL has no host.
        urlHostOrNull(url) != null &&
        isParsableUrl(url)

private fun isParsableUrl(url: String): Boolean = try {
    isValidHost(Url(url).host)
} catch (_: URLParserException) {
    false
} catch (_: IllegalArgumentException) {
    false
}

/** `true` if OkHttp accepts [host]: a host name or a bracketed IPv6 address. */
private fun isValidHost(host: String): Boolean =
    if (host.startsWith('[')) {
        host.endsWith(']') && isIpv6Address(host.substring(1, host.length - 1))
    } else {
        host
            .removeSuffix(".")
            .split('.')
            .all(::isHostLabel)
    }

private fun isHostLabel(label: String): Boolean =
    label.length in 1..HOST_LABEL_MAX_LENGTH &&
            label.none { it <= ' ' || it == '\u007F' || it in HOST_FORBIDDEN_CHARS }

/**
 * `true` if [address] is an IPv6 address without a zone id, such as
 * `fd00::5` or `::ffff:10.0.0.1`.
 */
private fun isIpv6Address(address: String): Boolean {
    val halves = address.split("::")
    if (halves.size > 2) return false
    val groups = halves.flatMap { half ->
        if (half.isEmpty()) emptyList() else half.split(':')
    }
    // A dotted IPv4 address may only end the address
    // and stands for two groups.
    val hasIpv4 = halves.last().isNotEmpty() && REGEX_IPV4.matches(groups.last())
    val hexGroups = if (hasIpv4) groups.dropLast(1) else groups
    val count = hexGroups.size + if (hasIpv4) 2 else 0
    val isCountValid = if (halves.size == 2) {
        // The `::` stands for one or more zero groups.
        count < IPV6_GROUP_COUNT
    } else {
        count == IPV6_GROUP_COUNT
    }
    return isCountValid && hexGroups.all(::isIpv6Group)
}

private fun isIpv6Group(group: String): Boolean =
    group.length in 1..IPV6_GROUP_MAX_LENGTH &&
            group.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

/** The lowercase scheme of [url], what comes before `://`; empty if it has none. */
internal fun urlSchemeOf(url: String): String = url.substringBefore("://", missingDelimiterValue = "").lowercase()

/**
 * The host of [url] as written: what follows `://` up to the path, query or fragment,
 * without the user info and the port; `null` if [url] has no `://` or the host is empty.
 * Ktor's `Url` falls back to `localhost` for a missing host, so check this first.
 */
internal fun urlHostOrNull(url: String): String? = url
    .substringAfter("://", missingDelimiterValue = "")
    .takeWhile { it != '/' && it != '?' && it != '#' }
    .substringAfterLast('@')
    .substringBefore(':')
    .takeIf(String::isNotEmpty)

/**
 * @return human-readable variant of [validateUrl] result, or `null` if
 * there's no validation error.
 */
suspend fun ValidationUrl.format(scope: TranslatorScope): String? =
    when (this) {
        ValidationUrl.ERROR_EMPTY -> scope.translate(Res.string.error_must_not_be_blank)
        ValidationUrl.ERROR_INVALID -> scope.translate(Res.string.error_invalid_url)
        else -> null
    }
