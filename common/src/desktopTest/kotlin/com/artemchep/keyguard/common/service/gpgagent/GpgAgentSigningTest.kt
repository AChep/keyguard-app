package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.crypto.GpgTestKeyFixtures
import com.artemchep.keyguard.common.util.hexToByteArray
import com.artemchep.keyguard.crypto.GpgAgentCryptoJvm
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyConverter
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.security.Signature
import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the GPG signing helpers in [GpgAgentCryptoJvm].
 *
 * The fixtures are real, unprotected (passphrase-less) OpenPGP secret keys
 * exported by GnuPG — exactly the shape the agent receives in production — so
 * these tests double as interop checks: each key signs a digest, the returned
 * Assuan `sig-val` S-expression is parsed back into r/s, and the reconstructed
 * signature is verified against the key's own public key.
 */
class GpgAgentSigningTest {
    @BeforeTest
    fun setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Test
    fun `ed25519 sig-val reconstructs a verifiable signature`() {
        val hash = sha256("keyguard ed25519 signing test".toByteArray())

        val response = GpgAgentCryptoJvm().signHash(
            privateKeyArmored = GpgTestKeyFixtures.ED25519,
            metadataKey = GpgAgentKeyMetadataKey(keygrip = "", fingerprint = ""),
            hashAlgorithm = "sha256",
            hash = hash,
        )

        val (r, s) = parseSigVal(response.sexp, algorithm = "eddsa")
        // gpg reads eddsa r/s as opaque fixed-width data: each must be the full
        // 32 bytes (no leading-zero stripping), reconstructing the 64-byte sig.
        assertEquals(32, r.size, "eddsa r must be 32 bytes")
        assertEquals(32, s.size, "eddsa s must be 32 bytes")

        val signature = r + s
        val verifier = Signature.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        verifier.initVerify(publicKeyOf(GpgTestKeyFixtures.ED25519))
        verifier.update(hash)
        assertTrue(verifier.verify(signature), "Ed25519 signature must verify")
    }

    @Test
    fun `ecdsa sig-val reconstructs a verifiable signature`() {
        val hash = sha256("keyguard ecdsa signing test".toByteArray())

        val response = GpgAgentCryptoJvm().signHash(
            privateKeyArmored = GpgTestKeyFixtures.ECDSA,
            metadataKey = GpgAgentKeyMetadataKey(keygrip = "", fingerprint = ""),
            hashAlgorithm = "sha256",
            hash = hash,
        )

        val (r, s) = parseSigVal(response.sexp, algorithm = "ecdsa")
        // gpg reads ecdsa r/s as MPIs (value-based), so re-encode them into the
        // DER SEQUENCE { INTEGER r, INTEGER s } that JCA verification expects.
        val der = DERSequence(
            ASN1EncodableVector().apply {
                add(ASN1Integer(BigInteger(1, r)))
                add(ASN1Integer(BigInteger(1, s)))
            },
        ).encoded

        val verifier = Signature.getInstance("NONEwithECDSA", BouncyCastleProvider.PROVIDER_NAME)
        verifier.initVerify(publicKeyOf(GpgTestKeyFixtures.ECDSA))
        verifier.update(hash)
        assertTrue(verifier.verify(der), "ECDSA signature must verify")
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun parseSigVal(
        sexp: String,
        algorithm: String,
    ): Pair<ByteArray, ByteArray> {
        assertTrue(
            sexp.startsWith("(sig-val($algorithm("),
            "Unexpected sig-val for $algorithm: $sexp",
        )
        val r = Regex("\\(r #([0-9A-Fa-f]+)#\\)").find(sexp)
            ?.groupValues?.get(1)
            ?: error("missing r in $sexp")
        val s = Regex("\\(s #([0-9A-Fa-f]+)#\\)").find(sexp)
            ?.groupValues?.get(1)
            ?: error("missing s in $sexp")
        return r.hexToByteArray() to s.hexToByteArray()
    }

    private fun publicKeyOf(armored: String): PublicKey {
        val collection = PGPSecretKeyRingCollection(
            PGPUtil.getDecoderStream(ByteArrayInputStream(armored.encodeToByteArray())),
            JcaKeyFingerprintCalculator(),
        )
        val signingKey = collection.keyRings.asSequence()
            .flatMap { it.secretKeys.asSequence() }
            .first { it.isSigningKey }
        return JcaPGPKeyConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getPublicKey(signingKey.publicKey)
    }
}
