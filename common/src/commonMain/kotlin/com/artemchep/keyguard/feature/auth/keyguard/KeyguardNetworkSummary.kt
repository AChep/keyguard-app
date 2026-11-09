package com.artemchep.keyguard.feature.auth.keyguard

import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_connection_default_relay
import io.ktor.http.Url

/** The relay of the [network]: "Keyguard relay", or the host of a custom one. */
suspend fun TranslatorScope.translateKeyguardRelayName(
    network: P2pNetwork,
): String {
    val relayUrl = network.relayUrl
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return translate(Res.string.app_link_connection_default_relay)
    return runCatching { Url(relayUrl).host }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }
        ?: relayUrl
}
