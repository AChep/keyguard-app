package com.artemchep.keyguard.common.service.sshagent

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPrivateKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Production-native SSH signing checked with independent JCA/BC test oracles. */
class SshAgentSigningTest {
    @Test
    fun `native Ed25519 signing returns raw verifiable signature`() {
        val kpg = Ed25519KeyPairGenerator()
        kpg.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = kpg.generateKeyPair()
        val privateKey = keyPair.private as Ed25519PrivateKeyParameters
        val publicKey = keyPair.public as Ed25519PublicKeyParameters
        val data = "verification test".encodeToByteArray()

        val result = SshAgentRequestProcessorJvm.signWithPrivateKey(
            privateKeyPem = toOpenSshPrivateKeyPem(privateKey),
            publicKeyOpenSsh = toOpenSshPublicKey(publicKey),
            data = data,
            flags = 0,
        )

        assertEquals("ssh-ed25519", result.algorithm)
        assertEquals(64, result.signature.size)
        val verifier = Ed25519Signer()
        verifier.init(false, publicKey)
        verifier.update(data, 0, data.size)
        assertTrue(verifier.verifySignature(result.signature))
    }

    @Test
    fun `native RSA signing preserves algorithm precedence and verifies`() {
        val keyPair = generateJcaRsaKeyPair()
        val publicKey = keyPair.public as RSAPublicKey
        val privateKeyPem = toPkcs8PrivateKeyPem(keyPair.private)
        val data = "RSA verification test".encodeToByteArray()
        val cases = listOf(
            Triple(0, "ssh-rsa", "SHA1withRSA"),
            Triple(0x02, "rsa-sha2-256", "SHA256withRSA"),
            Triple(0x04, "rsa-sha2-512", "SHA512withRSA"),
            Triple(0x06, "rsa-sha2-512", "SHA512withRSA"),
            Triple(0x80, "ssh-rsa", "SHA1withRSA"),
        )

        cases.forEach { (flags, expectedAlgorithm, verifierAlgorithm) ->
            val result = SshAgentRequestProcessorJvm.signWithPrivateKey(
                privateKeyPem = privateKeyPem,
                data = data,
                flags = flags,
            )
            assertEquals(expectedAlgorithm, result.algorithm)
            val verifier = Signature.getInstance(verifierAlgorithm)
            verifier.initVerify(publicKey)
            verifier.update(data)
            assertTrue(verifier.verify(result.signature), "signature for flags=$flags")
        }
    }

    @Test
    fun `native RSA signing reconstructs incomplete n d PKCS8 using public key`() {
        val keyPair = generateJcaRsaKeyPair()
        val privateKey = keyPair.private as RSAPrivateCrtKey
        val publicKey = keyPair.public as RSAPublicKey
        val nonCrtPrivateKey = KeyFactory.getInstance("RSA").generatePrivate(
            RSAPrivateKeySpec(privateKey.modulus, privateKey.privateExponent),
        )
        val data = "non-CRT RSA verification test".encodeToByteArray()

        val result = SshAgentRequestProcessorJvm.signWithPrivateKey(
            privateKeyPem = toPkcs8PrivateKeyPem(nonCrtPrivateKey),
            publicKeyOpenSsh = toOpenSshPublicKey(publicKey),
            data = data,
            flags = 0x02,
        )

        assertEquals("rsa-sha2-256", result.algorithm)
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(data)
        assertTrue(verifier.verify(result.signature))
    }

    @Test
    fun `native signing rejects a supplied public key from another identity`() {
        val edGenerator = Ed25519KeyPairGenerator()
        edGenerator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val firstEd = edGenerator.generateKeyPair()
        val secondEd = edGenerator.generateKeyPair()
        assertFailsWith<Exception> {
            SshAgentRequestProcessorJvm.signWithPrivateKey(
                privateKeyPem = toOpenSshPrivateKeyPem(firstEd.private as Ed25519PrivateKeyParameters),
                publicKeyOpenSsh = toOpenSshPublicKey(secondEd.public as Ed25519PublicKeyParameters),
                data = "mismatched Ed25519 identity".encodeToByteArray(),
                flags = 0,
            )
        }

        val firstRsa = generateJcaRsaKeyPair()
        val secondRsa = generateJcaRsaKeyPair()
        assertFailsWith<Exception> {
            SshAgentRequestProcessorJvm.signWithPrivateKey(
                privateKeyPem = toPkcs8PrivateKeyPem(firstRsa.private),
                publicKeyOpenSsh = toOpenSshPublicKey(secondRsa.public as RSAPublicKey),
                data = "mismatched RSA identity".encodeToByteArray(),
                flags = 0x02,
            )
        }
    }

    @Test
    fun `native signing rejects invalid PEM`() {
        assertFailsWith<Exception> {
            SshAgentRequestProcessorJvm.signWithPrivateKey(
                privateKeyPem = "not a valid PEM key",
                data = "data".encodeToByteArray(),
                flags = 0,
            )
        }
    }

    @Test
    fun `extractKeyType preserves OpenSSH text behavior`() {
        assertEquals("ssh-ed25519", SshAgentRequestProcessorJvm.extractKeyType("ssh-ed25519 AAAA... comment"))
        assertEquals("ssh-rsa", SshAgentRequestProcessorJvm.extractKeyType("ssh-rsa AAAA... user@host"))
        assertEquals(
            "ecdsa-sha2-nistp256",
            SshAgentRequestProcessorJvm.extractKeyType("ecdsa-sha2-nistp256 AAAA..."),
        )
        assertEquals("ssh-ed25519", SshAgentRequestProcessorJvm.extractKeyType("ssh-ed25519\tAAAA... comment"))
        assertEquals("", SshAgentRequestProcessorJvm.extractKeyType(""))
    }

    @Test
    fun `publicKeysMatch accepts tab-delimited OpenSSH public keys`() {
        val blob = byteArrayOf(1, 2, 3, 4, 5)
        val encodedBlob = Base64.getEncoder().encodeToString(blob)
        val tabDelimitedKey = "ssh-ed25519\t$encodedBlob comment"
        val spaceDelimitedKey = "ssh-ed25519 $encodedBlob comment"

        assertTrue(SshAgentRequestProcessorJvm.publicKeysMatch(tabDelimitedKey, spaceDelimitedKey))
        assertFalse(
            SshAgentRequestProcessorJvm.publicKeysMatch(
                tabDelimitedKey,
                "ssh-ed25519 ${Base64.getEncoder().encodeToString(byteArrayOf(5, 4, 3, 2, 1))} comment",
            ),
        )
    }

    private fun toOpenSshPrivateKeyPem(key: Ed25519PrivateKeyParameters): String {
        val body = Base64.getEncoder().encodeToString(OpenSSHPrivateKeyUtil.encodePrivateKey(key))
        return buildString {
            appendLine("-----BEGIN OPENSSH PRIVATE KEY-----")
            body.chunked(70).forEach(::appendLine)
            appendLine("-----END OPENSSH PRIVATE KEY-----")
        }
    }

    private fun toOpenSshPublicKey(key: Ed25519PublicKeyParameters): String =
        "ssh-ed25519 ${Base64.getEncoder().encodeToString(OpenSSHPublicKeyUtil.encodePublicKey(key))}"
}
