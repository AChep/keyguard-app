package com.artemchep.keyguard.feature.auth.common.util

import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ValidationUrl {
    OK,
    ERROR_EMPTY,
    ERROR_INVALID,
}

/**
 * Good characters for Internationalized Resource Identifiers (IRI).
 * This comprises most common used Unicode characters allowed in IRI
 * as detailed in RFC 3987.
 * Specifically, those two byte Unicode characters are not included.
 */
private const val GOOD_IRI_CHAR = "a-zA-Z0-9\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF"

/**
 * RFC 1035 Section 2.3.4 limits the labels to a maximum 63 octets.
 */
private const val IRI = "[$GOOD_IRI_CHAR]([$GOOD_IRI_CHAR\\-]{0,61}[$GOOD_IRI_CHAR]){0,1}"

private const val GOOD_GTLD_CHAR = "a-zA-Z\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF"

private const val GTLD = "[$GOOD_GTLD_CHAR]{2,63}"

private const val HOST_NAME = "($IRI\\.)+$GTLD"

private const val IP_ADDRESS =
    "((25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9])\\.(25[0-5]|2[0-4]" +
            "[0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9]|0)\\.(25[0-5]|2[0-4][0-9]|[0-1]" +
            "[0-9]{2}|[1-9][0-9]|[1-9]|0)\\.(25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}" +
            "|[1-9][0-9]|[0-9]))"

private const val DOMAIN_NAME = "($HOST_NAME|$IP_ADDRESS)"

/**
 * Regular expression pattern to match most part of RFC 3987
 * Internationalized URLs, aka IRIs. Commonly used Unicode characters are
 * added.
 */
val REGEX_WEB_URL =
    (
            "((?:(http|https|Http|Https|rtsp|Rtsp):\\/\\/(?:(?:[a-zA-Z0-9\\$\\-\\_\\.\\+\\!\\*\\'\\(\\)" +
                    "\\,\\;\\?\\&\\=]|(?:\\%[a-fA-F0-9]{2})){1,64}(?:\\:(?:[a-zA-Z0-9\\$\\-\\_" +
                    "\\.\\+\\!\\*\\'\\(\\)\\,\\;\\?\\&\\=]|(?:\\%[a-fA-F0-9]{2})){1,25})?\\@)?)?" +
                    "(?:" + DOMAIN_NAME + ")" +
                    "(?:\\:\\d{1,5})?)" + // plus option port number
                    "(\\/(?:(?:[" + GOOD_IRI_CHAR + "\\;\\/\\?\\:\\@\\&\\=\\#\\~" + // plus option query params
                    "\\-\\.\\+\\!\\*\\'\\(\\)\\,\\_])|(?:\\%[a-fA-F0-9]{2}))*)?" +
                    "(?:\\b|$)"
            ).toRegex()

fun validateUrl(
    url: String?,
    allowBlank: Boolean = false,
): ValidationUrl {
    return if (url == null || url.isBlank()) {
        if (allowBlank) {
            ValidationUrl.OK
        } else {
            ValidationUrl.ERROR_EMPTY
        }
    } else if (!REGEX_WEB_URL.matches(url)) {
        ValidationUrl.ERROR_INVALID
    } else {
        ValidationUrl.OK
    }
}

/**
 * @return human-readable variant of [validateEmail] result, or `null` if
 * there's no validation error.
 */
suspend fun ValidationUrl.format(scope: TranslatorScope): String? =
    when (this) {
        ValidationUrl.ERROR_EMPTY -> scope.translate(Res.string.error_must_not_be_blank)
        ValidationUrl.ERROR_INVALID -> scope.translate(Res.string.error_invalid_url)
        else -> null
    }

fun Flow<String>.validatedUrl(
    scope: TranslatorScope,
    allowBlank: Boolean = false,
) = this
    .map { rawUrl ->
        val url = rawUrl
            .trim()
        val urlError = validateUrl(url, allowBlank = allowBlank)
            .format(scope)
        if (urlError != null) {
            Validated.Failure(
                model = url,
                error = urlError,
            )
        } else {
            Validated.Success(
                model = url,
            )
        }
    }
