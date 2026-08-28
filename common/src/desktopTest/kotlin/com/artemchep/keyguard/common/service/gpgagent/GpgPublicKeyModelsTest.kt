package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.test.gpgMetadata
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

    @Test
    fun `structural operations cannot widen the authorized policy snapshot`() = createSecret(
        keys = listOf(
            createMetadataKey(
                keygrip = KEYGRIP_2,
                capabilities = setOf("decrypt"),
            ),
        ),
        certificates = listOf(
            GpgAgentCertificateMetadata(
                primaryFingerprint = FINGERPRINT_1,
                components = listOf(
                    GpgAgentKeyComponentMetadata(
                        fingerprint = FINGERPRINT_1,
                        role = GpgAgentKeyComponentRole.PRIMARY,
                        publicKeyAlgorithmId = 22,
                        algorithm = "EDDSA",
                        keygrips = listOf(KEYGRIP_1),
                        storedSecretMaterial = true,
                        agentOperations = setOf(GpgAgentOperation.SIGN),
                    ),
                    GpgAgentKeyComponentMetadata(
                        fingerprint = FINGERPRINT_2,
                        role = GpgAgentKeyComponentRole.SUBKEY,
                        publicKeyAlgorithmId = 18,
                        algorithm = "ECDH",
                        keygrips = listOf(KEYGRIP_2),
                        storedSecretMaterial = false,
                        agentOperations = setOf(GpgAgentOperation.DECRYPT),
                    ),
                ),
            ),
            GpgAgentCertificateMetadata(
                primaryFingerprint = FINGERPRINT_3,
                components = listOf(
                    GpgAgentKeyComponentMetadata(
                        fingerprint = FINGERPRINT_3,
                        role = GpgAgentKeyComponentRole.PRIMARY,
                        publicKeyAlgorithmId = 1,
                        algorithm = "RSA",
                        keygrips = listOf(KEYGRIP_3),
                        storedSecretMaterial = false,
                        agentOperations = setOf(
                            GpgAgentOperation.SIGN,
                            GpgAgentOperation.DECRYPT,
                        ),
                    ),
                ),
            ),
        ),
    ).toGpgPublicKeyEntry(name = null).let { entry ->
        // Secret-material filtering is per certificate: the first certificate stores secret material
        // so only its secret component is routable, while the second certificate is
        // public-only and keeps its primary.
        assertEquals(
            listOf(KEYGRIP_1, KEYGRIP_3),
            entry.keyInfo.map { key -> key.keygrip },
        )
        // The authorization snapshot grants nothing, so no structural row may claim
        // signing regardless of the inventory's key flags.
        assertTrue(entry.keyInfo.none { key -> key.canSign })
        assertTrue(!entry.canSign)
    }

    @Test
    fun `live policy snapshot overlays signing authorization onto structural inventory`() = createSecret(
        certificates = listOf(
            GpgAgentCertificateMetadata(
                primaryFingerprint = FINGERPRINT_1,
                components = listOf(
                    GpgAgentKeyComponentMetadata(
                        fingerprint = FINGERPRINT_1,
                        role = GpgAgentKeyComponentRole.PRIMARY,
                        publicKeyAlgorithmId = 22,
                        algorithm = "EDDSA",
                        keygrips = listOf(KEYGRIP_1),
                        storedSecretMaterial = true,
                        agentOperations = setOf(GpgAgentOperation.SIGN),
                    ),
                ),
            ),
        ),
        authorization = GpgAgentAuthorizationSnapshot(
            evaluatedAtEpochSeconds = 1,
            policyRevision = GpgAgentAuthorizationSnapshot.SUPPORTED_POLICY_REVISION,
            keys = listOf(
                createMetadataKey(
                    keygrip = KEYGRIP_1,
                    capabilities = setOf("sign"),
                ),
            ),
            revocations = mapOf(FINGERPRINT_1 to GpgRevocationStatus.NOT_REVOKED),
        ),
    ).toGpgPublicKeyEntry(name = null).let { entry ->
        assertTrue(entry.canSign)
        assertTrue(entry.keyInfo.single().canSign)
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
        certificates: List<GpgAgentCertificateMetadata> = emptyList(),
        authorization: GpgAgentAuthorizationSnapshot? = null,
    ) = GpgAgentSecret(
        cipher = createCipher(),
        privateKeyArmored = privateKeyArmored,
        publicKeyArmored = publicKeyArmored,
        fingerprint = fingerprint,
        metadata = certificates
            .takeIf { it.isNotEmpty() }
            ?.let(::GpgAgentKeyMetadata)
            ?: gpgMetadata(*keys.toTypedArray()),
        authorization = authorization ?: GpgAgentAuthorizationSnapshot(
            evaluatedAtEpochSeconds = 1,
            policyRevision = GpgAgentAuthorizationSnapshot.SUPPORTED_POLICY_REVISION,
            keys = keys,
            revocations = keys.associate { it.fingerprint.normalizeGpgFingerprint() to GpgRevocationStatus.NOT_REVOKED },
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
        const val KEYGRIP_3 = "2123456789ABCDEF0123456789ABCDEF01234567"
        const val FINGERPRINT_1 = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"
        const val FINGERPRINT_2 = "BBCDEF0123456789ABCDEF0123456789ABCDEF02"
        const val FINGERPRINT_3 = "CBCDEF0123456789ABCDEF0123456789ABCDEF03"
    }
}
