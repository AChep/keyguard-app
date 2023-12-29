package com.artemchep.keyguard.provider.bitwarden.usecase

import io.ktor.http.URLProtocol

/**
 * @author Artem Chepurnyi
 */
object CipherUnsecureUrlCheckUtils {
    private val internalUnsecureToSecureProtocols = listOf(
        URLProtocol.HTTP to URLProtocol.HTTPS,
        URLProtocol.WS to URLProtocol.WSS,
    )

    val unsecureToSecureProtocols = internalUnsecureToSecureProtocols
        .associateBy { it.first.name }
        // values -- secure protocols
        .mapValues { it.value.second }

    val unsecureProtocols = internalUnsecureToSecureProtocols
        // values -- un-secure protocols
        .map { it.first }
}
