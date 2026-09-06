package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.test.gpgMetadata
import kotlin.test.Test
import kotlin.test.assertEquals

class GpgKeyMaterialTest {
    @Test
    fun `generated key exposes only portable cryptographic material`() {
        val generated = generatedKey()

        assertEquals(
            GpgKeyMaterial(
                privateKeyArmored = "private-old",
                publicKeyArmored = "public-old",
                fingerprint = "FINGERPRINT-OLD",
                metadata = oldMetadata,
            ),
            generated.toGpgKeyMaterial(),
        )
    }

    @Test
    fun `replacing material preserves generator presentation fields`() {
        val generated = generatedKey()
        val replacement = GpgKeyMaterial(
            privateKeyArmored = "private-new",
            publicKeyArmored = "public-new",
            fingerprint = "FINGERPRINT-NEW",
            metadata = newMetadata,
        )

        assertEquals(
            GeneratedGpgKey(
                privateKeyArmored = replacement.privateKeyArmored,
                publicKeyArmored = replacement.publicKeyArmored,
                fingerprint = replacement.fingerprint,
                metadata = replacement.metadata,
                userId = "Alice <alice@example.test>",
                typeLabel = "Ed25519 + X25519",
            ),
            generated.withGpgKeyMaterial(replacement),
        )
    }

    private fun generatedKey() = GeneratedGpgKey(
        privateKeyArmored = "private-old",
        publicKeyArmored = "public-old",
        fingerprint = "FINGERPRINT-OLD",
        metadata = oldMetadata,
        userId = "Alice <alice@example.test>",
        typeLabel = "Ed25519 + X25519",
    )

    private companion object {
        val oldMetadata = gpgMetadata(
            GpgAgentKeyMetadataKey(
                    keygrip = "KEYGRIP-OLD",
                    fingerprint = "FINGERPRINT-OLD",
                    capabilities = setOf("sign"),
            ),
        )
        val newMetadata = gpgMetadata(
            GpgAgentKeyMetadataKey(
                    keygrip = "KEYGRIP-NEW",
                    fingerprint = "FINGERPRINT-NEW",
                    capabilities = setOf("decrypt"),
            ),
        )
    }
}
