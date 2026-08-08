package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class GpgPublicKeyModelsTest {
    @Test
    fun `entry falls back to the first component key fingerprint`() = createSecret(
        fingerprint = " ",
        keys = listOf(
            createMetadataKey(
                keygrip = KEYGRIP_1,
                fingerprint = "",
            ),
            createMetadataKey(
                keygrip = KEYGRIP_2,
                fingerprint = FINGERPRINT_1,
            ),
        ),
    ).toGpgPublicKeyEntry(name = null).let { entry ->
        assertEquals(FINGERPRINT_1, entry.primaryFingerprint)
    }

    @Test
    fun `blank armored key maps to a null column`() = createSecret(
        publicKeyArmored = "  ",
    ).toGpgPublicKeyEntry(name = null).let { entry ->
        assertNull(entry.publicKeyArmored)
    }

    @Test
    fun `unusable component keys are excluded from key info`() = createSecret(
        keys = listOf(
            createMetadataKey(
                keygrip = KEYGRIP_1,
            ),
            // No capabilities and no keygrip make a key
            // unusable for the agent.
            createMetadataKey(
                keygrip = KEYGRIP_2,
                capabilities = emptySet(),
            ),
            createMetadataKey(
                keygrip = "",
            ),
        ),
    ).toGpgPublicKeyEntry(name = null).let { entry ->
        assertEquals(listOf(KEYGRIP_1), entry.keyInfo.map { it.keygrip })
    }

    @Test
    fun `capabilities are masked without private key material`() = createSecret(
        privateKeyArmored = null,
    ).toGpgPublicKeyEntry(name = null).let { entry ->
        assertTrue(!entry.canSign && !entry.canDecrypt)
        assertTrue(entry.keyInfo.none { it.canSign || it.canDecrypt })
    }

    @Test
    fun `keygrips are normalized and component fingerprints fall back to the secret`() =
        createSecret(
            keys = listOf(
                createMetadataKey(
                    keygrip = KEYGRIP_1.lowercase(),
                    fingerprint = "",
                ),
            ),
        ).toGpgPublicKeyEntry(name = null).let { entry ->
            val key = entry.keyInfo.single()
            assertEquals(KEYGRIP_1, key.keygrip)
            assertEquals(FINGERPRINT_1, key.fingerprint)
        }

    private fun createSecret(
        privateKeyArmored: String? = "private-key",
        publicKeyArmored: String? = "public-key",
        fingerprint: String? = FINGERPRINT_1,
        keys: List<GpgAgentKeyMetadataKey> = listOf(
            createMetadataKey(
                keygrip = KEYGRIP_1,
            ),
        ),
    ) = GpgAgentSecret(
        cipher = createCipher(),
        privateKeyArmored = privateKeyArmored,
        publicKeyArmored = publicKeyArmored,
        fingerprint = fingerprint,
        metadata = GpgAgentKeyMetadata(
            keys = keys,
        ),
    )

    private fun createMetadataKey(
        keygrip: String,
        fingerprint: String = FINGERPRINT_1,
        capabilities: Set<String> = setOf("sign", "decrypt"),
    ) = GpgAgentKeyMetadataKey(
        keygrip = keygrip,
        fingerprint = fingerprint,
        algorithm = "ED25519",
        capabilities = capabilities,
    )

    private fun createCipher(): DSecret = DSecret(
        id = "cipher",
        accountId = "account",
        folderId = null,
        organizationId = null,
        collectionIds = emptySet(),
        revisionDate = Instant.parse("2024-01-01T00:00:00Z"),
        createdDate = Instant.parse("2024-01-01T00:00:00Z"),
        archivedDate = null,
        deletedDate = null,
        service = BitwardenService(),
        name = "Cipher",
        notes = "",
        favorite = false,
        reprompt = false,
        synced = true,
        type = DSecret.Type.GpgKey,
    )

    private companion object {
        const val KEYGRIP_1 = "0123456789ABCDEF0123456789ABCDEF01234567"
        const val KEYGRIP_2 = "1123456789ABCDEF0123456789ABCDEF01234567"
        const val FINGERPRINT_1 = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"
    }
}
