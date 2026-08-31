package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.crypto.NativeGpgCertificateMaterialReconciler
import com.artemchep.keyguard.crypto.NativeGpgKeyMetadataResolver
import com.artemchep.keyguard.crypto.NativeGpgPublicKeyParser
import com.artemchep.keyguard.crypto.parseGpgSecretKeyRingCollection
import com.artemchep.keyguard.provider.bitwarden.usecase.refreshRevocationCertificates
import com.artemchep.keyguard.test.generatedGpgKey
import org.bouncycastle.openpgp.PGPSignature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GpgKeyEditorImportReconcilerJvmTest {
    private val subject = GpgKeyEditorImportReconciler(
        materialReconciler = NativeGpgCertificateMaterialReconciler,
        metadataResolver = NativeGpgKeyMetadataResolver,
        publicKeyParser = NativeGpgPublicKeyParser,
    )

    @Test
    fun `same certificate import preserves secret material and adds revocation`() {
        val material = refreshRevocationCertificates()
        val existing = key(
            privateKey = material.privateKey,
            publicKey = material.original,
            fingerprint = material.fingerprint,
            metadata = GpgAgentKeyMetadata(),
        )
        val incoming = key(
            privateKey = "",
            publicKey = material.compromised,
            fingerprint = material.fingerprint,
            metadata = null,
        )

        val merged = assertIs<GpgKeyEditorImportResult.Success>(
            subject(existing = existing, incoming = incoming),
        ).key

        assertEquals(material.fingerprint, merged.fingerprint)
        assertTrue(merged.privateKeyArmored.isNotBlank())
        val parsedPublic = assertIs<GpgPublicKeyParseResult.Success>(
            NativeGpgPublicKeyParser.parse(merged.publicKeyArmored),
        ).keys.single()
        assertTrue(parsedPublic.revoked)
        val privateRing = parseGpgSecretKeyRingCollection(merged.privateKeyArmored)
            .keyRings
            .asSequence()
            .single()
        assertTrue(
            privateRing.publicKey
                .getSignaturesOfType(PGPSignature.KEY_REVOCATION)
                .hasNext(),
            "Reconciliation dropped the imported revocation from private material.",
        )
        val metadata = assertNotNull(merged.metadata)
        assertNotEquals(GpgAgentKeyMetadata(), metadata)
        assertTrue(metadata.certificates.single().components.size >= 2)
        assertTrue(
            metadata.certificates.single().components.all { it.storedSecretMaterial },
        )
        assertEquals(parsedPublic.userIds.firstOrNull().orEmpty(), merged.userId)
        assertEquals(parsedPublic.algorithm, merged.typeLabel)

        val repeated = assertIs<GpgKeyEditorImportResult.Success>(
            subject(existing = merged, incoming = incoming),
        ).key
        assertEquals(merged.privateKeyArmored, repeated.privateKeyArmored)
        assertEquals(merged.publicKeyArmored, repeated.publicKeyArmored)
        assertEquals(merged.fingerprint, repeated.fingerprint)
        assertEquals(merged.userId, repeated.userId)
        assertEquals(merged.typeLabel, repeated.typeLabel)
    }

    @Test
    fun `matching stored fingerprint cannot join different actual certificates`() {
        val existingMaterial = refreshRevocationCertificates()
        val incomingMaterial = refreshRevocationCertificates()
        assertNotEquals(existingMaterial.fingerprint, incomingMaterial.fingerprint)
        val forgedExisting = key(
            privateKey = existingMaterial.privateKey,
            publicKey = existingMaterial.original,
            fingerprint = incomingMaterial.fingerprint,
            metadata = GpgAgentKeyMetadata(),
        )
        val incoming = key(
            privateKey = "",
            publicKey = incomingMaterial.original,
            fingerprint = incomingMaterial.fingerprint,
            metadata = null,
        )

        val error = assertIs<GpgKeyEditorImportResult.Error>(
            subject(existing = forgedExisting, incoming = incoming),
        )

        assertEquals(GpgKeyEditorImportError.FingerprintMismatch, error.reason)
    }

    private fun key(
        privateKey: String,
        publicKey: String,
        fingerprint: String,
        metadata: GpgAgentKeyMetadata?,
    ) = generatedGpgKey(
        privateKey = privateKey,
        publicKey = publicKey,
        fingerprint = fingerprint,
        metadata = metadata,
        userId = "stale user ID",
        typeLabel = "stale algorithm",
    )
}
