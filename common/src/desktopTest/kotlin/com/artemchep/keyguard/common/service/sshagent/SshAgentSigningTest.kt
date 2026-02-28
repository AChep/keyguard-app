package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.SshAgentFilter
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import org.bouncycastle.util.encoders.Base64
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature as JcaSignature
import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the cryptographic signing functions in [SshAgentIpcServer].
 * These tests use dynamically generated keys rather than embedded fixtures
 * because BouncyCastle's OpenSSH key serialization is deterministic.
 */
class SshAgentSigningTest {
    private val logRepository = object : LogRepository {
        override suspend fun add(
            tag: String,
            message: String,
            level: LogLevel,
        ) {
            // Do nothing
        }
    }

    // Minimal stub — vault is never accessed in signing tests.
    private val stubVaultSession = object : GetVaultSession {
        override val valueOrNull: MasterSession? = null
        override fun invoke(): Flow<MasterSession> = flowOf()
    }

    private val sshAgentFilter = object : GetSshAgentFilter {
        override fun invoke(): Flow<SshAgentFilter> = flowOf(SshAgentFilter())
    }

    private val server = SshAgentIpcServer(
        logRepository = logRepository,
        getVaultSession = stubVaultSession,
        getSshAgentFilter = sshAgentFilter,
        authToken = ByteArray(32),
        scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
    )

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
        val result = server.signEd25519(privateKey, data)

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
        val result = server.signEd25519(privateKey, data)

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

        val sig1 = server.signEd25519(privateKey, "data A".toByteArray())
        val sig2 = server.signEd25519(privateKey, "data B".toByteArray())

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
        val result = server.signRsa(kp.first, "test".toByteArray(), flags = 0x02)

        assertEquals("rsa-sha2-256", result.algorithm)
        assertTrue(result.signature.isNotEmpty())
    }

    @Test
    fun `signRsa with SHA512 flag produces correct algorithm`() {
        val kp = generateRsaKeyPair()
        val result = server.signRsa(kp.first, "test".toByteArray(), flags = 0x04)

        assertEquals("rsa-sha2-512", result.algorithm)
        assertTrue(result.signature.isNotEmpty())
    }

    @Test
    fun `signRsa with no flags produces ssh-rsa algorithm`() {
        val kp = generateRsaKeyPair()
        val result = server.signRsa(kp.first, "test".toByteArray(), flags = 0)

        assertEquals("ssh-rsa", result.algorithm)
        assertTrue(result.signature.isNotEmpty())
    }

    @Test
    fun `signRsa SHA256 signature verifies correctly`() {
        val (bcPrivate, jcaPublic) = generateRsaKeyPair()
        val data = "RSA verification test".toByteArray()
        val result = server.signRsa(bcPrivate, data, flags = 0x02)

        val verifier = JcaSignature.getInstance("SHA256withRSA")
        verifier.initVerify(jcaPublic)
        verifier.update(data)
        assertTrue(verifier.verify(result.signature), "RSA-SHA256 signature should verify")
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
        val result = server.signWithPrivateKey(pem, data, flags = 0)

        assertEquals("ssh-ed25519", result.algorithm)

        // Verify.
        val verifier = Ed25519Signer()
        verifier.init(false, publicKey)
        verifier.update(data, 0, data.size)
        assertTrue(verifier.verifySignature(result.signature), "PEM-derived signature should verify")
    }

    @Test
    fun `signWithPrivateKey with invalid PEM throws exception`() {
        assertFailsWith<Exception> {
            server.signWithPrivateKey("not a valid PEM key", "data".toByteArray(), 0)
        }
    }

    // ================================================================
    // extractKeyType
    // ================================================================

    @Test
    fun `extractKeyType returns key type from OpenSSH public key`() {
        assertEquals("ssh-ed25519", server.extractKeyType("ssh-ed25519 AAAA... comment"))
        assertEquals("ssh-rsa", server.extractKeyType("ssh-rsa AAAA... user@host"))
        assertEquals("ecdsa-sha2-nistp256", server.extractKeyType("ecdsa-sha2-nistp256 AAAA..."))
    }

    @Test
    fun `extractKeyType returns empty string for empty input`() {
        // split("") returns [""], so firstOrNull() returns "".
        assertEquals("", server.extractKeyType(""))
    }

    // ================================================================
    // Helpers
    // ================================================================

    /**
     * Generates an RSA key pair and returns (BouncyCastle private, JCA public).
     */
    private fun generateRsaKeyPair(): Pair<org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters, RSAPublicKey> {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()

        val jcaPrivate = kp.private as java.security.interfaces.RSAPrivateCrtKey
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
}
