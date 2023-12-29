package com.artemchep.keyguard.feature.auth.common.util

import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res

enum class ValidationUri {
    OK,
    ERROR_EMPTY,
    ERROR_INVALID,
}

/**
 * Example: "http". Also called 'protocol'.
 * Scheme component is optional, even though the RFC doesn't make it optional. Since this regex is validating a
 * submitted callback url, which determines where the browser will navigate to after a successful authentication,
 * the browser will use http or https for the scheme by default.
 * Not borrowed from dperini in order to allow any scheme type.
 */
private const val REGEX_SCHEME = "[A-Za-z][+-.\\w^_]*:"

// Example: "//".
private const val REGEX_AUTHORITATIVE_DECLARATION = "/{2}"

// Optional component. Example: "suzie:abc123@". The use of the format "user:password" is deprecated.
private const val REGEX_USERINFO = "(?:\\S+(?::\\S*)?@)?"

// Examples: "google.com", "22.231.113.64".
private const val REGEX_HOST =
    "(?:" + // @Author = http://www.regular-expressions.info/examples.html
            // IP address
            "(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)" +
            "|" + // host name
            "(?:(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)" + // domain name
            "(?:\\.(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)*" + // TLD identifier must have >= 2 characters
            "(?:\\.(?:[a-z\\u00a1-\\uffff]{2,})))"

// Example: ":8042".
private const val REGEX_PORT = "(?::\\d{2,5})?"

// Example: "/search?foo=bar#element1".
private const val REGEX_RESOURCE_PATH = "(?:/\\S*)?"

val REGEX_WEB_URI = (
        "^(?:(?:" + REGEX_SCHEME + REGEX_AUTHORITATIVE_DECLARATION + ")?" +
                REGEX_USERINFO + REGEX_HOST + REGEX_PORT + REGEX_RESOURCE_PATH + ")$"
        ).toRegex()

fun validateUri(
    uri: String?,
    allowBlank: Boolean = false,
): ValidationUri {
    return if (uri == null || uri.isBlank()) {
        if (allowBlank) {
            ValidationUri.OK
        } else {
            ValidationUri.ERROR_EMPTY
        }
    } else if (!REGEX_WEB_URI.matches(uri)) {
        ValidationUri.ERROR_INVALID
    } else {
        ValidationUri.OK
    }
}

fun ValidationUri.format(scope: TranslatorScope): String? =
    when (this) {
        ValidationUri.ERROR_EMPTY -> scope.translate(Res.strings.error_must_not_be_blank)
        ValidationUri.ERROR_INVALID -> scope.translate(Res.strings.error_invalid_uri)
        else -> null
    }
