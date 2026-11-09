package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.model.Premium
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardRelayDefaults
import com.artemchep.keyguard.provider.keyguard.transport.RelayEndpoint
import io.ktor.http.Url

/** Linking needs a purchase unless the device uses a self-hosted relay server. */
internal fun canLinkApps(premium: Premium, network: P2pNetwork): Boolean {
    if (premium == Premium.PURCHASED) return true
    val host = network.relayUrl?.trim()?.let(::relayHost)
    val hosted = Url(KeyguardRelayDefaults.RELAY_URL).host.trimEnd('.')
    return host != null && !host.equals(hosted, ignoreCase = true)
}

private fun relayHost(relayUrl: String): String? = runCatching {
    require(relayUrl.none(Char::isWhitespace))
    // Url supplies localhost when the authority is missing; that is not a custom relay.
    require(RelayEndpoint.hostOrNull(relayUrl) != null)
    val url = Url(relayUrl)
    require(url.protocol.name in RELAY_SCHEMES)
    url.host.trimEnd('.').takeIf(String::isNotEmpty)
}.getOrNull()

private val RELAY_SCHEMES = setOf("https", "wss", "http", "ws")
