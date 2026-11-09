package com.artemchep.keyguard.core.store.bitwarden

import kotlin.test.Test
import kotlin.test.assertFalse

class P2pMembershipTest {
    @Test
    fun `a membership hides its secrets from its text`() {
        val membership = P2pMembership(
            groupId = "group",
            groupKeyBase64 = "group-key",
            founderId = "device",
            device = P2pDevice(
                deviceId = "device",
                label = "Phone",
                platform = "android",
                noisePublicKeyBase64 = "noise-public",
                noisePrivateKeyBase64 = "noise-private",
                kemPublicKeyBase64 = "kem-public",
                kemPrivateKeyBase64 = "kem-private",
                signPublicKeyBase64 = "sign-public",
                signPrivateKeyBase64 = "sign-private",
            ),
            network = P2pNetwork(
                relayUrl = "wss://relay.example.com",
                accessToken = "token",
            ),
        )
        val text = membership.toString()
        listOf("group-key", "noise-private", "sign-private", "token").forEach { secret ->
            assertFalse(secret in text, "The text shows $secret: $text")
        }
    }
}
