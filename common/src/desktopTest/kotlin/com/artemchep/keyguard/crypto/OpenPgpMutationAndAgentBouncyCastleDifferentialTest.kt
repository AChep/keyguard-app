package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.GpgKeyMaterial
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationChange
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationRequest
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationResult
import com.artemchep.keyguard.common.service.crypto.GpgTestKeyFixtures
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.GpgSExpr
import com.artemchep.keyguard.common.service.gpgagent.encodeCanonical
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Permanent BC 1.84 differential coverage for OpenPGP mutation and agent behavior. */
class OpenPgpMutationAndAgentBouncyCastleDifferentialTest {
    @Test
    fun `expiration preserves unselected packets and matches BC semantics`() {
        val originalSecret = secretRing(GpgTestKeyFixtures.CV25519)
        val originalPublic = originalSecret.toCertificate()
        val primaryFingerprint = originalPublic.publicKey.fingerprintHex()
        val material = GpgKeyMaterial(
            privateKeyArmored = GpgTestKeyFixtures.CV25519,
            publicKeyArmored = originalPublic.armored(),
            fingerprint = primaryFingerprint,
            metadata = GpgAgentKeyMetadata(),
        )
        val referenceTime = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val target = referenceTime + 365.days
        val request = GpgKeyExpirationRequest(
            key = material,
            change = GpgKeyExpirationChange(
                expiresAt = target,
                componentFingerprints = setOf(primaryFingerprint),
            ),
        )

        val bc = assertIs<GpgKeyExpirationResult.Success>(
            GpgKeyExpirationServiceJvm(
                metadataResolver = NativeGpgKeyMetadataResolver,
                now = { referenceTime },
                waitForClock = {},
            ).update(request),
        ).key
        val native = assertIs<GpgKeyExpirationResult.Success>(
            NativeGpgKeyExpirationService.update(request),
        ).key

        assertEquals(primaryFingerprint, bc.fingerprint)
        assertEquals(primaryFingerprint, native.fingerprint)
        assertEquals(target.epochSeconds, primaryExpirationEpochSeconds(bc.publicKeyArmored))
        assertEquals(target.epochSeconds, primaryExpirationEpochSeconds(native.publicKeyArmored))
        assertEquals(bc.metadata, native.metadata)

        val originalSubkeys = originalPublic.publicKeys.asSequence()
            .filterNot(PGPPublicKey::isMasterKey)
            .associate { key -> key.fingerprintHex() to key.encoded }
        assertTrue(originalSubkeys.isNotEmpty())
        listOf(bc, native).forEach { updated ->
            val updatedSubkeys = publicRing(updated.publicKeyArmored).publicKeys.asSequence()
                .filterNot(PGPPublicKey::isMasterKey)
                .associateBy(PGPPublicKey::fingerprintHex)
            originalSubkeys.forEach { (fingerprint, encoded) ->
                assertTrue(
                    encoded.contentEquals(updatedSubkeys.getValue(fingerprint).encoded),
                    "Unselected subkey packet changed for $fingerprint",
                )
            }
        }
    }

    @Test
    fun `agent Ed25519 signature matches BC`() {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("OpenPGP agent signature".encodeToByteArray())
        val metadataKey = GpgAgentKeyMetadataKey(
            keygrip = "",
            fingerprint = signingFingerprint(GpgTestKeyFixtures.ED25519),
        )

        val bc = GpgAgentCryptoJvm().signHash(
            privateKeyArmored = GpgTestKeyFixtures.ED25519,
            metadataKey = metadataKey,
            hashAlgorithm = "sha256",
            hash = hash,
        )
        val native = NativeGpgAgentCrypto.signHash(
            privateKeyArmored = GpgTestKeyFixtures.ED25519,
            metadataKey = metadataKey,
            hashAlgorithm = "sha256",
            hash = hash,
        )

        assertEquals(bc.sexp, native.sexp)
    }

    @Test
    fun `agent raw RSA decryption matches BC`() {
        val metadataKey = GpgAgentKeyMetadataKey(
            keygrip = "",
            fingerprint = encryptionFingerprint(GpgTestKeyFixtures.RSA),
        )
        val ciphertext = GpgSExpr.Listt(
            listOf(
                GpgSExpr.Atom("enc-val".encodeToByteArray()),
                GpgSExpr.Listt(
                    listOf(
                        GpgSExpr.Atom("rsa".encodeToByteArray()),
                        GpgSExpr.Listt(
                            listOf(
                                GpgSExpr.Atom("a".encodeToByteArray()),
                                GpgSExpr.Atom(byteArrayOf(0x02)),
                            ),
                        ),
                    ),
                ),
            ),
        ).encodeCanonical()

        val bc = GpgAgentCryptoJvm().pkdecrypt(
            privateKeyArmored = GpgTestKeyFixtures.RSA,
            metadataKey = metadataKey,
            ciphertext = ciphertext,
            unwrapEcdh = false,
        )
        val native = NativeGpgAgentCrypto.pkdecrypt(
            privateKeyArmored = GpgTestKeyFixtures.RSA,
            metadataKey = metadataKey,
            ciphertext = ciphertext,
            unwrapEcdh = false,
        )

        assertEquals(bc.valueSexp, native.valueSexp)
    }

    private fun primaryExpirationEpochSeconds(armored: String): Long? {
        val primary = publicRing(armored).publicKey
        val selfCertification = primary.userIDs.asSequence()
            .flatMap { userId -> primary.getSignaturesForID(userId).asSequence() }
            .filter { signature ->
                signature.keyID == primary.keyID && signature.signatureType in 0x10..0x13
            }
            .maxBy { signature -> signature.creationTime.time }
        val duration = selfCertification.hashedSubPackets.keyExpirationTime
        return duration.takeUnless { it == 0L }
            ?.let { seconds -> primary.creationTime.time / 1_000L + seconds }
    }

    private fun signingFingerprint(armored: String): String = secretRing(armored)
        .secretKeys
        .asSequence()
        .single { key -> key.isSigningKey }
        .fingerprintHex()

    private fun encryptionFingerprint(armored: String): String = secretRing(armored)
        .secretKeys
        .asSequence()
        .first { key -> !key.isMasterKey && key.publicKey.isEncryptionKey }
        .fingerprintHex()

    private fun secretRing(armored: String): PGPSecretKeyRing =
        org.bouncycastle.openpgp.PGPSecretKeyRingCollection(
            PGPUtil.getDecoderStream(ByteArrayInputStream(armored.encodeToByteArray())),
            JcaKeyFingerprintCalculator(),
        ).keyRings.asSequence().single()

    private fun publicRing(armored: String): PGPPublicKeyRing =
        org.bouncycastle.openpgp.PGPPublicKeyRingCollection(
            PGPUtil.getDecoderStream(ByteArrayInputStream(armored.encodeToByteArray())),
            JcaKeyFingerprintCalculator(),
        ).keyRings.asSequence().single()
}
