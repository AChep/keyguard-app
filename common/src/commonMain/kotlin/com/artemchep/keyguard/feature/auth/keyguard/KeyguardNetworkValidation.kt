package com.artemchep.keyguard.feature.auth.keyguard

import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.util.ValidationUrl
import com.artemchep.keyguard.feature.auth.common.util.format
import com.artemchep.keyguard.feature.auth.common.util.isServerUrl
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.provider.keyguard.transport.RelayEndpoint
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_connection_relay_token_error_invalid

internal val KEYGUARD_RELAY_URL_SCHEMES = setOf("wss", "https")

// The request for the ICE servers carries the
// access token, so it must be encrypted.
internal val KEYGUARD_ICE_URL_SCHEMES = setOf("https")

/**
 * Checks a URL of a relay service. An empty URL is valid: the
 * default one is used.
 */
internal fun validateKeyguardServerUrl(
    url: String,
    schemes: Set<String>,
): ValidationUrl = when {
    url.isEmpty() -> ValidationUrl.OK
    isServerUrl(url, schemes) -> ValidationUrl.OK
    else -> ValidationUrl.ERROR_INVALID
}

internal suspend fun TranslatorScope.validatedServerUrl(
    rawUrl: String,
    schemes: Set<String>,
): Validated<String> {
    val url = rawUrl.trim()
    val error = validateKeyguardServerUrl(url, schemes)
        .format(this)
    return if (error != null) {
        Validated.Failure(
            model = url,
            error = error,
        )
    } else {
        Validated.Success(
            model = url,
        )
    }
}

internal suspend fun TranslatorScope.validatedRelayToken(
    rawToken: String,
): Validated<String> {
    val token = rawToken.trim()
    // An empty token is valid: none is sent.
    return if (token.isEmpty() || RelayEndpoint.isValidToken(token)) {
        Validated.Success(
            model = token,
        )
    } else {
        Validated.Failure(
            model = token,
            error = translate(Res.string.app_link_connection_relay_token_error_invalid),
        )
    }
}
