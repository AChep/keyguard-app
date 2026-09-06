package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentAuthorizationSnapshot
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentCertificateMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyComponentMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyComponentRole
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMetadataResolution
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentOperation
import com.artemchep.keyguard.common.service.gpgagent.GpgRevocationStatus
import com.artemchep.keyguard.common.service.gpgagent.routableAgentKeys
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.test.gpgCanonicalMetadata
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpgKeyMetadataResolutionTest {

    @Test
    fun `non-canonical stored metadata is regenerated when material is touched`() {
        var resolutions = 0
        val resolver = object : GpgKeyMetadataResolver {
            override fun resolve(
                privateKeyArmored: String?,
                publicKeyArmored: String?,
                fingerprint: String?,
                candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
            ): GpgAgentMetadataResolution {
                resolutions += 1
                return RESOLUTION
            }
        }
        val old = key(metadata = GpgAgentKeyMetadata())

        val resolved = key(metadata = null).resolveGpgMetadata(old, resolver)

        assertEquals(1, resolutions)
        assertEquals(CANONICAL_METADATA, resolved.metadata)
    }

    @Test
    fun `canonical inventory is always refreshed when a resolver is available`() {
        var resolutions = 0
        val refreshed = RESOLUTION.copy(
            authorization = RESOLUTION.authorization.copy(
                evaluatedAtEpochSeconds = 2,
            ),
        )
        val resolver = object : GpgKeyMetadataResolver {
            override fun resolve(
                privateKeyArmored: String?,
                publicKeyArmored: String?,
                fingerprint: String?,
                candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
            ): GpgAgentMetadataResolution {
                resolutions += 1
                return refreshed
            }
        }
        val old = key(metadata = CANONICAL_METADATA)

        val resolvedSameMaterial = key(metadata = null).resolveGpgMetadata(old, resolver)
        val resolved = key(publicKey = "changed", metadata = null).resolveGpgMetadata(old, resolver)

        assertEquals(refreshed.metadata, resolvedSameMaterial.metadata)
        assertEquals(refreshed.metadata, resolved.metadata)
        assertEquals(2, resolutions)
    }

    @Test
    fun `canonical inventory is reused without a resolver`() {
        val old = key(metadata = CANONICAL_METADATA)

        val resolved = key(metadata = null).resolveGpgMetadata(old, resolver = null)

        assertEquals(CANONICAL_METADATA, resolved.metadata)
    }

    @Test
    fun `serialization persists only the certificate index`() {
        val encoded = Json.encodeToString(CANONICAL_METADATA)
        val decoded = Json.decodeFromString<GpgAgentKeyMetadata>(encoded)

        assertFalse(encoded.contains("authorization"))
        assertFalse(encoded.contains("evaluatedAtEpochSeconds"))
        assertFalse(encoded.contains("revocations"))
        assertEquals(CANONICAL_METADATA.certificates, decoded.certificates)
        assertEquals(listOf(POLICY_KEY), decoded.routableAgentKeys)
    }

    @Test
    fun `routable keys filter secret material per certificate not per inventory`() {
        // Secret-material routing is scoped per certificate: a certificate that stores
        // secret material exposes only its secret components, while a purely public
        // certificate keeps all of them.
        val publicOnly = GpgAgentCertificateMetadata(
            primaryFingerprint = OTHER_FINGERPRINT,
            components = listOf(
                GpgAgentKeyComponentMetadata(
                    fingerprint = OTHER_FINGERPRINT,
                    role = GpgAgentKeyComponentRole.PRIMARY,
                    publicKeyAlgorithmId = 22,
                    algorithm = "EDDSA",
                    keygrips = listOf(OTHER_KEYGRIP),
                    storedSecretMaterial = false,
                    agentOperations = setOf(GpgAgentOperation.SIGN),
                ),
            ),
        )
        val metadata = CANONICAL_METADATA.copy(
            certificates = CANONICAL_METADATA.certificates + publicOnly,
        )

        assertEquals(
            listOf(FINGERPRINT, OTHER_FINGERPRINT),
            metadata.routableAgentKeys.map { key -> key.fingerprint },
        )
    }

    @Test
    fun `routable keys drop non-secret components of a secret-bearing certificate`() {
        val mixed = GpgAgentCertificateMetadata(
            primaryFingerprint = OTHER_FINGERPRINT,
            components = listOf(
                GpgAgentKeyComponentMetadata(
                    fingerprint = OTHER_FINGERPRINT,
                    role = GpgAgentKeyComponentRole.PRIMARY,
                    publicKeyAlgorithmId = 22,
                    algorithm = "EDDSA",
                    keygrips = listOf(OTHER_KEYGRIP),
                    storedSecretMaterial = true,
                    agentOperations = setOf(GpgAgentOperation.SIGN),
                ),
                GpgAgentKeyComponentMetadata(
                    fingerprint = THIRD_FINGERPRINT,
                    role = GpgAgentKeyComponentRole.SUBKEY,
                    publicKeyAlgorithmId = 18,
                    algorithm = "ECDH",
                    keygrips = listOf(THIRD_KEYGRIP),
                    storedSecretMaterial = false,
                    agentOperations = setOf(GpgAgentOperation.DECRYPT),
                ),
            ),
        )
        val metadata = CANONICAL_METADATA.copy(certificates = listOf(mixed))

        assertEquals(
            listOf(OTHER_FINGERPRINT),
            metadata.routableAgentKeys.map { key -> key.fingerprint },
        )
    }

    @Test
    fun `old serialized fields are ignored and fail closed`() {
        val decoded = Json {
            ignoreUnknownKeys = true
        }.decodeFromString<GpgAgentKeyMetadata>(
            """{"version":1,"keys":[{"keygrip":"$KEYGRIP","fingerprint":"$FINGERPRINT"}]}""",
        )

        assertTrue(decoded.certificates.isEmpty())
        assertTrue(decoded.routableAgentKeys.isEmpty())
    }

    private fun key(
        publicKey: String = "public",
        metadata: GpgAgentKeyMetadata?,
    ) = BitwardenCipher.GpgKey(
        privateKeyArmored = "private",
        publicKeyArmored = publicKey,
        fingerprint = FINGERPRINT,
        metadata = metadata,
    )

    private companion object {
        const val FINGERPRINT = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"
        const val KEYGRIP = "0123456789ABCDEF0123456789ABCDEF01234567"
        const val OTHER_FINGERPRINT = "0123456789ABCDEF0123456789ABCDEF01234567"
        const val OTHER_KEYGRIP = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"
        const val THIRD_FINGERPRINT = "89ABCDEF0123456789ABCDEF0123456789ABCDEF"
        const val THIRD_KEYGRIP = "456789ABCDEF0123456789ABCDEF0123456789AB"

        val POLICY_KEY = GpgAgentKeyMetadataKey(
            keygrip = KEYGRIP,
            fingerprint = FINGERPRINT,
            algorithm = "EDDSA",
            capabilities = setOf("sign"),
        )

        val CANONICAL_METADATA = gpgCanonicalMetadata(
            fingerprint = FINGERPRINT,
            keygrip = KEYGRIP,
        )

        val RESOLUTION = GpgAgentMetadataResolution(
            metadata = CANONICAL_METADATA,
            authorization = GpgAgentAuthorizationSnapshot(
                evaluatedAtEpochSeconds = 1,
                policyRevision = GpgAgentAuthorizationSnapshot.SUPPORTED_POLICY_REVISION,
                keys = listOf(POLICY_KEY),
                revocations = mapOf(FINGERPRINT to GpgRevocationStatus.NOT_REVOKED),
            ),
        )
    }
}
