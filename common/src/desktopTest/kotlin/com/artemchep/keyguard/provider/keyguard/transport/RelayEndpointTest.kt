package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RelayEndpointTest {
    private val token = "relay-token-0123456789"

    @Test
    fun `the relay's own ice endpoint gets the token`() {
        val endpoint = RelayEndpoint("wss://relay.example.com", token = token)
        assertEquals("https://relay.example.com/v1/ice-servers", endpoint.iceServersUrl)
        assertEquals(token, endpoint.iceToken)
        // Another path on the same host is still the relay.
        val path = RelayEndpoint("https://relay.example.com", iceUrl = "https://Relay.Example.com/ice", token = token)
        assertEquals(token, path.iceToken)
    }

    @Test
    fun `an ice endpoint on another host, scheme or port never gets the token`() {
        listOf(
            "https://ice.example.com/v1/ice-servers",
            "http://relay.example.com/v1/ice-servers",
            "https://relay.example.com:8443/v1/ice-servers",
            "https://relay.example.com.evil.example/v1/ice-servers",
            "https://relay.example.com@evil.example/v1/ice-servers",
        ).forEach { iceUrl ->
            val endpoint = RelayEndpoint("wss://relay.example.com", iceUrl = iceUrl, token = token)
            assertNull(endpoint.iceToken, iceUrl)
            // The relay itself keeps it.
            assertEquals(token, endpoint.token)
        }
    }

    @Test
    fun `a pairing code gets the token only if it names the saved relay`() {
        val network = P2pNetwork(relayUrl = "wss://relay.example.com", accessToken = token)
        // The same relay, written another way.
        val same = KeyguardRelayDefaults.endpointOfCode("https://relay.example.com/v1/ws", null, network)
        assertEquals(token, same.token)
        assertEquals(token, same.iceToken)
        assertEquals(token, KeyguardRelayDefaults.endpointOfCode("wss://Relay.Example.com:443/", null, network).token)
        assertNull(KeyguardRelayDefaults.endpointOfCode("wss://relay.example.com:8443", null, network).token)
        // Another relay, or Keyguard's.
        assertNull(KeyguardRelayDefaults.endpointOfCode("wss://evil.example", null, network).token)
        assertNull(KeyguardRelayDefaults.endpointOfCode(null, null, network).token)
        // The saved relay with an ICE endpoint elsewhere: the relay gets it, the ICE endpoint not.
        val ice = KeyguardRelayDefaults.endpointOfCode("wss://relay.example.com", "https://evil.example/ice", network)
        assertEquals(token, ice.token)
        assertNull(ice.iceToken)
    }

    @Test
    fun `a token saved for keyguard's relay goes to keyguard's relay only`() {
        val network = P2pNetwork(accessToken = token)
        assertEquals(token, KeyguardRelayDefaults.endpointOfCode(null, null, network).token)
        assertEquals(token, KeyguardRelayDefaults.endpointOfCode(KeyguardRelayDefaults.RELAY_URL, null, network).token)
        assertNull(KeyguardRelayDefaults.endpointOfCode("wss://evil.example", null, network).token)
    }
}
