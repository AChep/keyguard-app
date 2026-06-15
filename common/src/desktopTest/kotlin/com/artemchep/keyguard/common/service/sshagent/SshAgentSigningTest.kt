package com.artemchep.keyguard.common.service.sshagent

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.util.encoders.Base64
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature as JcaSignature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the cryptographic signing helpers in [SshAgentRequestProcessorJvm].
 * These tests use dynamically generated keys rather than embedded fixtures
 * because BouncyCastle's OpenSSH key serialization is deterministic.
 */
class SshAgentSigningTest {
    // ================================================================
    // Ed25519 signing
    // ================================================================

    @Test
    fun `signEd25519 produces a 64-byte signature`() {
        val kpg = Ed25519KeyPairGenerator()
        kpg.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val kp = kpg.generateKeyPair()
        val privateKey = kp.private as Ed25519PrivateKeyParameters

        val data = "test data to sign".toByteArray()
        val result = SshAgentRequestProcessorJvm.signEd25519(privateKey, data)

        assertEquals("ssh-ed25519", result.algorithm)
        assertEquals(64, result.signature.size)
    }

    @Test
    fun `signEd25519 signature verifies correctly`() {
        val kpg = Ed25519KeyPairGenerator()
        kpg.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val kp = kpg.generateKeyPair()
        val privateKey = kp.private as Ed25519PrivateKeyParameters
        val publicKey = kp.public as Ed25519PublicKeyParameters

        val data = "verification test".toByteArray()
        val result = SshAgentRequestProcessorJvm.signEd25519(privateKey, data)

        // Verify signature using BouncyCastle verifier.
        val verifier = Ed25519Signer()
        verifier.init(false, publicKey)
        verifier.update(data, 0, data.size)
        assertTrue(verifier.verifySignature(result.signature), "Signature should verify")
    }

    @Test
    fun `signEd25519 produces different signatures for different data`() {
        val kpg = Ed25519KeyPairGenerator()
        kpg.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val kp = kpg.generateKeyPair()
        val privateKey = kp.private as Ed25519PrivateKeyParameters

        val sig1 = SshAgentRequestProcessorJvm.signEd25519(privateKey, "data A".toByteArray())
        val sig2 = SshAgentRequestProcessorJvm.signEd25519(privateKey, "data B".toByteArray())

        // Ed25519 is deterministic for same key+data but should differ for different data.
        assertTrue(
            !sig1.signature.contentEquals(sig2.signature),
            "Signatures for different data should differ",
        )
    }

    // ================================================================
    // RSA signing
    // ================================================================

    @Test
    fun `signRsa with SHA256 flag produces correct algorithm`() {
        val kp = generateRsaKeyPair()
        val result = SshAgentRequestProcessorJvm.signRsa(kp.first, "test".toByteArray(), flags = 0x02)

        assertEquals("rsa-sha2-256", result.algorithm)
        assertTrue(result.signature.isNotEmpty())
    }

    @Test
    fun `signRsa with SHA512 flag produces correct algorithm`() {
        val kp = generateRsaKeyPair()
        val result = SshAgentRequestProcessorJvm.signRsa(kp.first, "test".toByteArray(), flags = 0x04)

        assertEquals("rsa-sha2-512", result.algorithm)
        assertTrue(result.signature.isNotEmpty())
    }

    @Test
    fun `signRsa with no flags produces ssh-rsa algorithm`() {
        val kp = generateRsaKeyPair()
        val result = SshAgentRequestProcessorJvm.signRsa(kp.first, "test".toByteArray(), flags = 0)

        assertEquals("ssh-rsa", result.algorithm)
        assertTrue(result.signature.isNotEmpty())
    }

    @Test
    fun `signRsa SHA256 signature verifies correctly`() {
        val (bcPrivate, jcaPublic) = generateRsaKeyPair()
        val data = "RSA verification test".toByteArray()
        val result = SshAgentRequestProcessorJvm.signRsa(bcPrivate, data, flags = 0x02)

        val verifier = JcaSignature.getInstance("SHA256withRSA")
        verifier.initVerify(jcaPublic)
        verifier.update(data)
        assertTrue(verifier.verify(result.signature), "RSA-SHA256 signature should verify")
    }

    @Test
    fun `signRsa accepts non-CRT RSA key parameters`() {
        val kp = generateJcaRsaKeyPair()
        val privateKey = kp.private as RSAPrivateCrtKey
        val publicKey = kp.public as RSAPublicKey
        val nonCrtPrivateKey = RSAKeyParameters(
            true,
            privateKey.modulus,
            privateKey.privateExponent,
        )
        val data = "non-CRT RSA verification test".toByteArray()

        val result = SshAgentRequestProcessorJvm.signRsa(nonCrtPrivateKey, data, flags = 0x02)

        assertEquals("rsa-sha2-256", result.algorithm)
        val verifier = JcaSignature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(data)
        assertTrue(verifier.verify(result.signature), "Non-CRT RSA signature should verify")
    }

    // ================================================================
    // signWithPrivateKey (PEM parsing + signing)
    // ================================================================

    @Test
    fun `signWithPrivateKey with Ed25519 PEM produces valid signature`() {
        val kpg = Ed25519KeyPairGenerator()
        kpg.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val kp = kpg.generateKeyPair()
        val privateKey = kp.private as Ed25519PrivateKeyParameters
        val publicKey = kp.public as Ed25519PublicKeyParameters

        val pem = toOpenSshPrivateKeyPem(privateKey)
        val data = "PEM signing test".toByteArray()
        val result = SshAgentRequestProcessorJvm.signWithPrivateKey(pem, data, flags = 0)

        assertEquals("ssh-ed25519", result.algorithm)

        // Verify.
        val verifier = Ed25519Signer()
        verifier.init(false, publicKey)
        verifier.update(data, 0, data.size)
        assertTrue(verifier.verifySignature(result.signature), "PEM-derived signature should verify")
    }

    @Test
    fun `signWithPrivateKey accepts PKCS8 RSA PEM`() {
        val kp = generateJcaRsaKeyPair()
        val publicKey = kp.public as RSAPublicKey
        val data = "PKCS8 RSA verification test".toByteArray()
        val result = SshAgentRequestProcessorJvm.signWithPrivateKey(
            privateKeyPem = toPkcs8PrivateKeyPem(kp.private),
            data = data,
            flags = 0x02,
        )

        assertEquals("rsa-sha2-256", result.algorithm)

        val verifier = JcaSignature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(data)
        assertTrue(verifier.verify(result.signature), "PKCS#8 RSA signature should verify")
    }

    @Test
    fun `signWithPrivateKey with invalid PEM throws exception`() {
        assertFailsWith<Exception> {
            SshAgentRequestProcessorJvm.signWithPrivateKey("not a valid PEM key", "data".toByteArray(), 0)
        }
    }

    // ================================================================
    // extractKeyType
    // ================================================================

    @Test
    fun `extractKeyType returns key type from OpenSSH public key`() {
        assertEquals("ssh-ed25519", SshAgentRequestProcessorJvm.extractKeyType("ssh-ed25519 AAAA... comment"))
        assertEquals("ssh-rsa", SshAgentRequestProcessorJvm.extractKeyType("ssh-rsa AAAA... user@host"))
        assertEquals("ecdsa-sha2-nistp256", SshAgentRequestProcessorJvm.extractKeyType("ecdsa-sha2-nistp256 AAAA..."))
    }

    @Test
    fun `extractKeyType returns key type from tab-delimited OpenSSH public key`() {
        assertEquals("ssh-ed25519", SshAgentRequestProcessorJvm.extractKeyType("ssh-ed25519\tAAAA... comment"))
    }

    @Test
    fun `extractKeyType returns empty string for empty input`() {
        // split("") returns [""], so firstOrNull() returns "".
        assertEquals("", SshAgentRequestProcessorJvm.extractKeyType(""))
    }

    @Test
    fun `publicKeysMatch accepts tab-delimited OpenSSH public keys`() {
        val blob = byteArrayOf(1, 2, 3, 4, 5)
        val encodedBlob = Base64.toBase64String(blob)
        val tabDelimitedKey = "ssh-ed25519\t$encodedBlob comment"
        val spaceDelimitedKey = "ssh-ed25519 $encodedBlob comment"

        assertTrue(
            SshAgentRequestProcessorJvm.publicKeysMatch(tabDelimitedKey, spaceDelimitedKey),
        )
        assertFalse(
            SshAgentRequestProcessorJvm.publicKeysMatch(
                tabDelimitedKey,
                "ssh-ed25519 ${Base64.toBase64String(byteArrayOf(5, 4, 3, 2, 1))} comment",
            ),
        )
    }

    // ================================================================
    // Helpers
    // ================================================================

    /**
     * Generates an RSA key pair and returns (BouncyCastle private, JCA public).
     */
    private fun generateRsaKeyPair(): Pair<org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters, RSAPublicKey> {
        val kp = generateJcaRsaKeyPair()
        val jcaPrivate = kp.private as RSAPrivateCrtKey
        val bcPrivate = org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters(
            jcaPrivate.modulus,
            jcaPrivate.publicExponent,
            jcaPrivate.privateExponent,
            jcaPrivate.primeP,
            jcaPrivate.primeQ,
            jcaPrivate.primeExponentP,
            jcaPrivate.primeExponentQ,
            jcaPrivate.crtCoefficient,
        )
        return bcPrivate to (kp.public as RSAPublicKey)
    }

    private fun generateJcaRsaKeyPair(): java.security.KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        return kpg.generateKeyPair()
    }

    /**
     * Serializes an Ed25519 private key to OpenSSH PEM format
     * for use with signWithPrivateKey.
     */
    private fun toOpenSshPrivateKeyPem(key: Ed25519PrivateKeyParameters): String {
        val blob = OpenSSHPrivateKeyUtil.encodePrivateKey(key)
        val b64 = Base64.toBase64String(blob)
        return buildString {
            appendLine("-----BEGIN OPENSSH PRIVATE KEY-----")
            b64.chunked(70).forEach { appendLine(it) }
            appendLine("-----END OPENSSH PRIVATE KEY-----")
        }
    }

    private fun toPkcs8PrivateKeyPem(privateKey: PrivateKey): String {
        val b64 = Base64.toBase64String(privateKey.encoded)
        return buildString {
            appendLine("-----BEGIN PRIVATE KEY-----")
            b64.chunked(70).forEach { appendLine(it) }
            appendLine("-----END PRIVATE KEY-----")
        }
    }
}
