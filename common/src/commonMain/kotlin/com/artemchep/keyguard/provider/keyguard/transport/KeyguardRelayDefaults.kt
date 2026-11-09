package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork

/** The relay a sync group uses unless its network settings name another one. */
object KeyguardRelayDefaults {
    /**
     * The relay Keyguard hosts.
     */
    const val RELAY_URL: String = "wss://relay.keyguard.dev"

    /** The ICE servers endpoint of the [RELAY_URL], the default one. */
    val ICE_URL: String get() = endpoint(relayUrl = null, iceUrl = null).iceServersUrl

    /**
     * Returns the relay of the [network] settings, or of the default relay.
     *
     * @throws IllegalArgumentException if the settings hold a malformed URL or token.
     */
    fun endpoint(network: P2pNetwork): RelayEndpoint = endpoint(
        relayUrl = network.relayUrl,
        iceUrl = network.iceUrl,
        token = network.accessToken,
    )

    /**
     * Returns the relay at [relayUrl], or the default one.
     *
     * @throws IllegalArgumentException if the URL or the token is malformed.
     */
    fun endpoint(
        relayUrl: String?,
        iceUrl: String?,
        token: String? = null,
    ): RelayEndpoint = RelayEndpoint(
        relayUrl = relayUrl?.takeIf { it.isNotBlank() } ?: RELAY_URL,
        iceUrl = iceUrl?.takeIf { it.isNotBlank() },
        token = token?.takeIf { it.isNotEmpty() },
    )

    /**
     * Returns the relay a pairing code names, at [relayUrl] or the default one, with the ICE
     * endpoint at [iceUrl]. Anyone can make a code that names any relay, so the access token
     * of the [network] goes along only to the relay it was saved for.
     *
     * @throws IllegalArgumentException if the code or the settings hold a malformed URL.
     */
    fun endpointOfCode(
        relayUrl: String?,
        iceUrl: String?,
        network: P2pNetwork,
    ): RelayEndpoint {
        val named = endpoint(relayUrl, iceUrl)
        val saved = endpoint(network)
        val token = network.accessToken.takeIf { named.webSocketUrl == saved.webSocketUrl }
        return endpoint(relayUrl, iceUrl, token)
    }
}
