package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.crypto.NativeGpgCertificateMaterialReconciler
import com.artemchep.keyguard.crypto.NativeGpgKeyMetadataResolver
import com.artemchep.keyguard.crypto.NativeGpgPublicKeyParser
import com.artemchep.keyguard.crypto.armored
import com.artemchep.keyguard.crypto.extractPrivateKeyEmptyPassphrase
import com.artemchep.keyguard.crypto.gpgBouncyCastleProvider
import com.artemchep.keyguard.crypto.parseGpgPublicKeyRingCollection
import com.artemchep.keyguard.crypto.parseGpgSecretKeyRingCollection
import com.artemchep.keyguard.crypto.verifiesSubkeyCertification
import com.artemchep.keyguard.provider.bitwarden.usecase.refreshRevocationCertificates
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@Suppress("FunctionNaming")
class CipherConflictResolverGpgNativeTest {
    @Test
    fun `replacement primary key retains local private material despite secret base`() {
        val baseMaterial = refreshRevocationCertificates()
        val replacementMaterial = refreshRevocationCertificates()
        assertNotEquals(baseMaterial.fingerprint, replacementMaterial.fingerprint)
        val base = cipher(
            publicMaterial = baseMaterial.original,
            privateMaterial = baseMaterial.privateKey,
            fingerprint = baseMaterial.fingerprint,
        )
        val local = cipher(
            publicMaterial = replacementMaterial.original,
            privateMaterial = replacementMaterial.privateKey,
            fingerprint = replacementMaterial.fingerprint,
        )
        val remote = cipher(
            publicMaterial = replacementMaterial.original,
            privateMaterial = null,
            fingerprint = replacementMaterial.fingerprint,
        )

        val resolution = resolveCipherConflict(
            base = base,
            local = local,
            remote = remote,
            at = MERGE_REVISION,
            preserveDisplacedSecretsInPasswordHistory = false,
            gpgCertificateMaterialReconciler = NativeGpgCertificateMaterialReconciler,
            gpgKeyMetadataResolver = NativeGpgKeyMetadataResolver,
        )
        val reconciledKey = assertNotNull(resolution.cipher.gpgKey)

        assertEquals(replacementMaterial.fingerprint, reconciledKey.fingerprint)
        assertNotNull(reconciledKey.privateKeyArmored)
        assertGpgMetadataHasComponents(reconciledKey)
        assertTrue(resolution.requiresRemoteWrite)
    }

    @Test
    fun `reconciliation preserves primary and subkey revocations and private material`() {
        val material = refreshRevocationCertificates()
        val targetSubkeyFingerprint = ring(material.original)
            .publicKeys
            .asSequence()
            .first { !it.isMasterKey }
            .fingerprint
            .copyOf()
        val remoteRevoked = material.compromised.withSubkeyRevocation(
            privateMaterial = material.privateKey,
            subkeyFingerprint = targetSubkeyFingerprint,
        )
        assertTrue(
            ring(remoteRevoked)
                .subkeyByFingerprint(targetSubkeyFingerprint)
                .getSignaturesOfType(PGPSignature.SUBKEY_REVOCATION)
                .hasNext(),
            "The remote fixture is missing its synthesized subkey revocation.",
        )
        val base = cipher(
            publicMaterial = material.original,
            privateMaterial = null,
            fingerprint = material.fingerprint,
        )
        val local = cipher(
            publicMaterial = material.original,
            privateMaterial = material.privateKey,
            fingerprint = material.fingerprint,
        )
        val remote = cipher(
            publicMaterial = remoteRevoked,
            privateMaterial = null,
            fingerprint = material.fingerprint,
        )

        val first = resolveCipherConflict(
            base = base,
            local = local,
            remote = remote,
            at = MERGE_REVISION,
            preserveDisplacedSecretsInPasswordHistory = false,
            gpgCertificateMaterialReconciler = NativeGpgCertificateMaterialReconciler,
            gpgKeyMetadataResolver = NativeGpgKeyMetadataResolver,
        )
        val firstKey = assertNotNull(first.cipher.gpgKey)
        val parsed = assertIs<GpgPublicKeyParseResult.Success>(
            NativeGpgPublicKeyParser.parse(assertNotNull(firstKey.publicKeyArmored)),
        )
        val mergedPublicRing = ring(assertNotNull(firstKey.publicKeyArmored))
        val metadata = assertNotNull(firstKey.metadata)

        assertTrue(parsed.keys.single().revoked)
        assertTrue(
            mergedPublicRing.publicKey
                .getSignaturesOfType(PGPSignature.KEY_REVOCATION)
                .hasNext(),
        )
        assertTrue(
            mergedPublicRing
                .subkeyByFingerprint(targetSubkeyFingerprint)
                .getSignaturesOfType(PGPSignature.SUBKEY_REVOCATION)
                .hasNext(),
            "Reconciliation dropped the target subkey revocation.",
        )
        assertNotNull(firstKey.privateKeyArmored)
        assertGpgMetadataHasComponents(firstKey)
        assertTrue(
            metadata
                .certificates
                .single()
                .components
                .all { it.storedSecretMaterial },
        )

        val repeated = resolveCipherConflict(
            base = null,
            local = first.cipher,
            remote = remote,
            at = MERGE_REVISION,
            preserveDisplacedSecretsInPasswordHistory = false,
            gpgCertificateMaterialReconciler = NativeGpgCertificateMaterialReconciler,
            gpgKeyMetadataResolver = NativeGpgKeyMetadataResolver,
        )
        val repeatedKey = assertNotNull(repeated.cipher.gpgKey)

        assertEquals(firstKey.publicKeyArmored, repeatedKey.publicKeyArmored)
        assertEquals(firstKey.privateKeyArmored, repeatedKey.privateKeyArmored)
        assertEquals(firstKey.fingerprint, repeatedKey.fingerprint)
    }

    @Test
    fun `reconciliation preserves the selected private material deletion`() {
        val material = refreshRevocationCertificates()
        val base = cipher(
            publicMaterial = material.original,
            privateMaterial = material.privateKey,
            fingerprint = material.fingerprint,
        )
        val local = base.copy(
            gpgKey = base.gpgKey?.copy(privateKeyArmored = null),
        )
        val remote = base.copy(notes = "remote notes")

        val resolution = resolveCipherConflict(
            base = base,
            local = local,
            remote = remote,
            at = MERGE_REVISION,
            preserveDisplacedSecretsInPasswordHistory = false,
            gpgCertificateMaterialReconciler = NativeGpgCertificateMaterialReconciler,
            gpgKeyMetadataResolver = NativeGpgKeyMetadataResolver,
        )
        val reconciledKey = assertNotNull(resolution.cipher.gpgKey)

        assertNotNull(reconciledKey.publicKeyArmored)
        assertNull(reconciledKey.privateKeyArmored)
        assertGpgMetadataHasComponents(reconciledKey)
    }

    @Test
    fun `reconciliation canonicalizes a formatting-only fingerprint difference`() {
        val material = refreshRevocationCertificates()
        val formattedFingerprint = material.fingerprint
            .lowercase()
            .chunked(4)
            .joinToString(" ")
        val local = cipher(
            publicMaterial = material.original,
            privateMaterial = null,
            fingerprint = material.fingerprint,
        )
        val remote = cipher(
            publicMaterial = material.original,
            privateMaterial = null,
            fingerprint = formattedFingerprint,
        )

        val resolution = resolveCipherConflict(
            base = null,
            local = local,
            remote = remote,
            at = MERGE_REVISION,
            preserveDisplacedSecretsInPasswordHistory = false,
            gpgCertificateMaterialReconciler = NativeGpgCertificateMaterialReconciler,
            gpgKeyMetadataResolver = NativeGpgKeyMetadataResolver,
        )
        val reconciledKey = assertNotNull(resolution.cipher.gpgKey)

        assertEquals(CipherConflictResolution.Mode.RemoteFallback, resolution.mode)
        assertEquals(material.fingerprint, reconciledKey.fingerprint)
        assertTrue(resolution.requiresRemoteWrite)
    }

    private fun cipher(
        publicMaterial: String,
        privateMaterial: String?,
        fingerprint: String,
    ) = BitwardenCipher(
        accountId = "account-1",
        cipherId = "cipher-1",
        revisionDate = BASE_REVISION,
        service = BitwardenService(version = BitwardenService.VERSION),
        name = "GPG key",
        notes = null,
        favorite = false,
        reprompt = BitwardenCipher.RepromptType.None,
        type = BitwardenCipher.Type.GpgKey,
        secureNote = BitwardenCipher.SecureNote(),
        gpgKey = BitwardenCipher.GpgKey(
            privateKeyArmored = privateMaterial,
            publicKeyArmored = publicMaterial,
            fingerprint = fingerprint,
        ),
    )

    private fun ring(armored: String) =
        parseGpgPublicKeyRingCollection(armored)
            .keyRings
            .asSequence()
            .single()

    private fun PGPPublicKeyRing.subkeyByFingerprint(fingerprint: ByteArray): PGPPublicKey =
        publicKeys
            .asSequence()
            .single { key ->
                !key.isMasterKey && key.fingerprint.contentEquals(fingerprint)
            }

    private fun String.withSubkeyRevocation(
        privateMaterial: String,
        subkeyFingerprint: ByteArray,
    ): String {
        val certificate = ring(this)
        val primary = certificate.publicKey
        val subkey = certificate.subkeyByFingerprint(subkeyFingerprint)
        val revocation = PGPSignatureGenerator(
            JcaPGPContentSignerBuilder(primary.algorithm, HashAlgorithmTags.SHA256)
                .setProvider(gpgBouncyCastleProvider),
            primary,
        ).apply {
            init(
                PGPSignature.SUBKEY_REVOCATION,
                parseGpgSecretKeyRingCollection(privateMaterial)
                    .keyRings
                    .asSequence()
                    .single()
                    .secretKey
                    .extractPrivateKeyEmptyPassphrase(),
            )
            setHashedSubpackets(
                PGPSignatureSubpacketGenerator().apply {
                    setIssuerFingerprint(false, primary)
                    setSignatureCreationTime(false, primary.creationTime)
                    setRevocationReason(false, 2, "test subkey revocation")
                }.generate(),
            )
        }.generateCertification(primary, subkey)
        check(revocation.verifiesSubkeyCertification(primary, subkey)) {
            "The synthesized subkey revocation signature is invalid."
        }

        // RFC 9580 orders a subkey revocation before its binding signatures.
        val bindings = subkey.signatures.asSequence().toList()
        var revokedSubkey = subkey
        bindings.forEach { signature ->
            revokedSubkey = PGPPublicKey.removeCertification(revokedSubkey, signature)
        }
        revokedSubkey = PGPPublicKey.addCertification(revokedSubkey, revocation)
        bindings.forEach { signature ->
            revokedSubkey = PGPPublicKey.addCertification(revokedSubkey, signature)
        }
        return PGPPublicKeyRing.insertPublicKey(certificate, revokedSubkey).armored()
    }

    private companion object {
        val BASE_REVISION = Instant.parse("2024-01-01T00:00:00Z")
        val MERGE_REVISION = Instant.parse("2024-01-02T00:00:00Z")
    }
}
