package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardRelayDefaults
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppLinkImportNetworkTest {
    private val token = "relay-token-0123456789"

    @Test
    fun `importing another relay clears the saved token`() = runTest {
        val network = P2pNetwork(accessToken = token)
        listOf(
            "wss://other.example",
            "https://other.example/v1/ws",
            "ws://relay.keyguard.dev",
        ).forEach { relay ->
            val imported = importNetwork(network, relay)

            assertEquals(relay, imported.relayUrl)
            assertNull(imported.accessToken, relay)
            assertNull(KeyguardRelayDefaults.endpoint(imported).token, relay)
        }
    }

    @Test
    fun `importing the same normalized relay preserves the saved token`() = runTest {
        val network = P2pNetwork(accessToken = token)
        listOf(null, "https://relay.keyguard.dev/v1/ws").forEach { relay ->
            val imported = importNetwork(network, relay)

            assertEquals(relay, imported.relayUrl)
            assertEquals(token, imported.accessToken)
            assertEquals(token, KeyguardRelayDefaults.endpoint(imported).token)
        }
    }

    @Test
    fun `an explicitly configured network overrides the bundle and keeps its token`() = runTest {
        val network = P2pNetwork(
            relayUrl = "wss://private.example",
            iceUrl = "https://private.example/ice",
            accessToken = token,
            allowLan = false,
        )

        val imported = importNetwork(network, "wss://other.example", "https://other.example/ice")

        assertEquals(network, imported)
        assertEquals(token, KeyguardRelayDefaults.endpoint(imported).token)
    }

    @Test
    fun `importing another ice endpoint keeps the token on the same relay`() = runTest {
        val network = P2pNetwork(accessToken = token)
        val iceUrl = "https://other.example/ice"

        val imported = importNetwork(network, relayUrl = null, iceUrl = iceUrl)
        val endpoint = KeyguardRelayDefaults.endpoint(imported)

        assertEquals(iceUrl, imported.iceUrl)
        assertEquals(token, imported.accessToken)
        assertEquals(token, endpoint.token)
        assertNull(endpoint.iceToken)
    }

    private suspend fun importNetwork(
        network: P2pNetwork,
        relayUrl: String?,
        iceUrl: String? = null,
    ): P2pNetwork = AppLinkTestDevice("Member").use { member ->
        AppLinkTestDevice("Joiner").use { joiner ->
            member.link()
            joiner.time = member.time + 1
            val device = joiner.newDevice()
            val bundle = member.admit(device).copy(relay = relayUrl, ice = iceUrl)

            joiner.service.import(bundle, device, network)

            joiner.membership().network
        }
    }
}
