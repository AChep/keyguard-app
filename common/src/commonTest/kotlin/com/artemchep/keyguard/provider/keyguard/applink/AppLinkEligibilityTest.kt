package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.model.Premium
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.provider.keyguard.transport.KeyguardRelayDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLinkEligibilityTest {
    @Test
    fun `only a purchase enables the hosted relay`() {
        val hosted = listOf(
            null,
            "",
            "  ",
            KeyguardRelayDefaults.RELAY_URL,
            "https://relay.keyguard.dev/v1/ws/",
            "wss://RELAY.KEYGUARD.DEV./",
            " wss://relay.keyguard.dev:443 ",
            "wss://relay.keyguard.dev:8443/another/path",
            "ws://relay.keyguard.dev",
        )
        Premium.entries.forEach { premium ->
            hosted.forEach { relay ->
                assertEquals(
                    premium == Premium.PURCHASED,
                    canLinkApps(premium, P2pNetwork(relayUrl = relay)),
                    "$premium, $relay",
                )
            }
        }
    }

    @Test
    fun `every status can use a self-hosted relay`() {
        val custom = listOf(
            "wss://relay.example.com",
            " https://Relay.Example.com:8443/custom/v1/ws/ ",
            "ws://127.0.0.1:7400",
            "http://localhost:7400",
            "wss://[::1]:7400",
        )
        Premium.entries.forEach { premium ->
            custom.forEach { relay ->
                assertTrue(canLinkApps(premium, P2pNetwork(relayUrl = relay)), "$premium, $relay")
            }
        }
    }

    @Test
    fun `invalid relay URLs do not grant eligibility`() {
        val invalid = listOf(
            "relay.example.com",
            "ftp://relay.example.com",
            "wss://",
            "wss:///missing-host",
            "wss://:443",
            "wss://relay example.com",
            "wss://relay.example.com:not-a-port",
        )
        listOf(Premium.NONE, Premium.INCLUDED).forEach { premium ->
            invalid.forEach { relay ->
                assertFalse(canLinkApps(premium, P2pNetwork(relayUrl = relay)), "$premium, $relay")
            }
        }
    }

    @Test
    fun `a custom ICE server or token does not replace a custom relay`() {
        val network = P2pNetwork(iceUrl = "https://ice.example.com", accessToken = "private-token")
        assertFalse(canLinkApps(Premium.INCLUDED, network))
    }
}
