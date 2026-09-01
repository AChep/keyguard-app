package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.model.GpgKeyConfig
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.crypto.NativeGpgKeyGenerator
import com.artemchep.keyguard.crypto.NativeGpgKeyMetadataResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class GpgCertificationAuthorityEntriesJvmTest {
    @Test
    fun `stale ownership metadata does not make a public-only cipher an authority`() {
        val persistedMetadata = requireNotNull(generated.metadata)
        assertTrue(
            persistedMetadata.certificates.single().components.any { component ->
                component.role == GpgAgentKeyComponentRole.PRIMARY &&
                        component.storedSecretMaterial
            },
        )

        listOf<String?>(null, " \n\t").forEach { privateKeyArmored ->
            val cipher = createCipher(
                generated = generated,
                privateKeyArmored = privateKeyArmored,
            )

            assertTrue(
                cipher.toGpgCertificationAuthorityEntries(
                    resolver = NativeGpgKeyMetadataResolver,
                ).isEmpty(),
            )
        }
    }

    @Test
    fun `nonblank malformed private material does not make a cipher an authority`() {
        val cipher = createCipher(
            generated = generated,
            privateKeyArmored = "not an OpenPGP private key",
        )

        assertTrue(
            cipher.toGpgCertificationAuthorityEntries(
                resolver = NativeGpgKeyMetadataResolver,
            ).isEmpty(),
        )
    }

    @Test
    fun `owned certification-only primary remains an authority`() {
        val resolution = requireNotNull(
            NativeGpgKeyMetadataResolver.resolve(
                privateKeyArmored = generated.privateKeyArmored,
                publicKeyArmored = generated.publicKeyArmored,
                fingerprint = generated.fingerprint,
            ),
        )
        val primaryFingerprint = generated.fingerprint.normalizeGpgFingerprint()
        val primaryAuthorization = resolution.authorization.keys.single { key ->
            key.fingerprint.normalizeGpgFingerprint() == primaryFingerprint
        }
        assertFalse(primaryAuthorization.canSign)
        assertTrue(
            resolution.metadata.certificates.single().components.any { component ->
                component.role == GpgAgentKeyComponentRole.PRIMARY &&
                        component.storedSecretMaterial
            },
        )

        val authority = createCipher(generated = generated)
            .toGpgCertificationAuthorityEntries(
                resolver = NativeGpgKeyMetadataResolver,
            )
            .single()

        assertEquals("account", authority.accountId)
        assertEquals("cipher", authority.cipherId)
        assertEquals(generated.publicKeyArmored, authority.publicKeyArmored)
        assertEquals(primaryFingerprint, authority.primaryFingerprint)
    }

    private fun createCipher(
        generated: GeneratedGpgKey,
        privateKeyArmored: String? = generated.privateKeyArmored,
    ): DSecret = DSecret(
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
        name = "Certification authority",
        notes = "",
        favorite = false,
        reprompt = false,
        synced = true,
        type = DSecret.Type.GpgKey,
        gpgKey = DSecret.GpgKey(
            privateKeyArmored = privateKeyArmored,
            publicKeyArmored = generated.publicKeyArmored,
            fingerprint = generated.fingerprint,
            metadata = generated.metadata,
        ),
    )

    private companion object {
        val generated: GeneratedGpgKey by lazy {
            NativeGpgKeyGenerator.generate(
                GpgKeyConfig.Modern(
                    userId = "Certification Authority <authority@test.invalid>",
                ),
            )
        }
    }
}
