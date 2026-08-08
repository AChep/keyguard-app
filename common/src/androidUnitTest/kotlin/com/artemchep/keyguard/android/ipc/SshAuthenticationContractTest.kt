package com.artemchep.keyguard.android.ipc

import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeCryptoSsh
import com.artemchep.keyguard.nativecrypto.NativeSshKeyType
import com.artemchep.keyguard.nativecrypto.NativeSshSignature
import org.openintents.ssh.authentication.SshAuthenticationApi
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SshAuthenticationContractTest {
    @Test
    fun `only the official SSH Authentication API version is accepted`() {
        assertTrue(
            isSupportedSshAuthenticationApiVersion(
                SshAuthenticationApi.API_VERSION,
            ),
        )
        assertFalse(
            isSupportedSshAuthenticationApiVersion(
                SshAuthenticationApi.API_VERSION + 1,
            ),
        )
    }

    @Test
    fun `Ed25519 public key is decoded to the exact X509 SPKI`() {
        val raw = ByteArray(32) { it.toByte() }
        val blob = sshBlob(
            "ssh-ed25519".encodeToByteArray(),
            raw,
        )
        val input = "ssh-ed25519 ${Base64.getEncoder().encodeToString(blob)} device comment"

        val decoded = NativeCrypto.ssh.decodePublicKey(input)
        assertEquals(NativeSshKeyType.ED25519, decoded.type)
        assertEquals("ssh-ed25519", decoded.algorithmName)
        assertEquals(SshAuthenticationApi.EDDSA, decoded.toSshAuthenticationApiAlgorithm())
        assertContentEquals(
            byteArrayOf(
                0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
                0x70, 0x03, 0x21, 0x00,
            ) + raw,
            decoded.spkiDer,
        )
    }

    @Test
    fun `RSA public key is decoded to X509 SPKI with official algorithm enum`() {
        val exponent = byteArrayOf(0x01, 0x00, 0x01)
        val modulus = ByteArray(128) { index ->
            if (index == 0) 0x01 else (index * 17 + 3).toByte()
        }.also { it[it.lastIndex] = (it.last().toInt() or 1).toByte() }
        val blob = sshBlob(
            "ssh-rsa".encodeToByteArray(),
            exponent,
            modulus,
        )

        val decoded = NativeCrypto.ssh.decodePublicKey(
            "ssh-rsa ${Base64.getEncoder().encodeToString(blob)}",
        )

        assertEquals(NativeSshKeyType.RSA, decoded.type)
        assertEquals("ssh-rsa", decoded.algorithmName)
        assertEquals(SshAuthenticationApi.RSA, decoded.toSshAuthenticationApiAlgorithm())
        val key = KeyFactory
            .getInstance("RSA")
            .generatePublic(
                java.security.spec.X509EncodedKeySpec(decoded.spkiDer),
            ) as RSAPublicKey
        assertEquals(65537, key.publicExponent.toInt())
        assertContentEquals(modulus, key.modulus.toByteArray().stripPositiveSignByte())
    }

    @Test
    fun `malformed or unsupported public keys are rejected by the shared decoder`() {
        val raw = ByteArray(32) { it.toByte() }
        val blob = sshBlob(
            "ssh-ed25519".encodeToByteArray(),
            raw,
        )
        val encoded = Base64.getEncoder().encodeToString(blob)
        // Declared type does not match the type embedded in the blob.
        assertFails { NativeCrypto.ssh.decodePublicKey("ssh-rsa $encoded") }
        // Trailing wire data after the key fields.
        val trailing = Base64.getEncoder().encodeToString(blob + byteArrayOf(0))
        assertFails { NativeCrypto.ssh.decodePublicKey("ssh-ed25519 $trailing") }
        // Unsupported key algorithm.
        val ecdsa = Base64.getEncoder().encodeToString(
            sshBlob("ecdsa-sha2-nistp256".encodeToByteArray()),
        )
        assertFails { NativeCrypto.ssh.decodePublicKey("ecdsa-sha2-nistp256 $ecdsa") }
        assertFails { NativeCrypto.ssh.decodePublicKey("") }
    }

    @Test
    fun `signature framing uses the RFC 4253 algorithm and signature strings`() {
        val signature = byteArrayOf(1, 3, 3, 7)
        val framed = NativeCryptoSsh.frameSignature(
            NativeSshSignature(
                algorithm = "rsa-sha2-512",
                signature = signature,
            ),
        )
        val reader = WireReader(framed)

        assertEquals("rsa-sha2-512", reader.read().decodeToString())
        assertContentEquals(signature, reader.read())
        assertEquals(0, reader.remaining)
    }

    @Test
    fun `RSA hash mapping uses RFC 8332 flags and rejects unsupported hashes`() {
        assertEquals(0, sshAgentSignatureFlags("ssh-rsa", SshAuthenticationApi.SHA1))
        assertEquals(0x02, sshAgentSignatureFlags("ssh-rsa", SshAuthenticationApi.SHA256))
        assertEquals(0x04, sshAgentSignatureFlags("ssh-rsa", SshAuthenticationApi.SHA512))
        assertNull(sshAgentSignatureFlags("ssh-rsa", SshAuthenticationApi.SHA224))
        assertNull(sshAgentSignatureFlags("ssh-rsa", SshAuthenticationApi.SHA384))
        assertNull(sshAgentSignatureFlags("ssh-rsa", SshAuthenticationApi.RIPEMD160))
        assertNull(sshAgentSignatureFlags("ssh-rsa", Int.MIN_VALUE))
    }

    @Test
    fun `Ed25519 ignores every API hash selection but rejects unknown values`() {
        assertEquals(0, sshAgentSignatureFlags("ssh-ed25519", SshAuthenticationApi.SHA1))
        assertEquals(0, sshAgentSignatureFlags("ssh-ed25519", SshAuthenticationApi.SHA224))
        assertEquals(0, sshAgentSignatureFlags("ssh-ed25519", SshAuthenticationApi.SHA256))
        assertEquals(0, sshAgentSignatureFlags("ssh-ed25519", SshAuthenticationApi.SHA384))
        assertEquals(0, sshAgentSignatureFlags("ssh-ed25519", SshAuthenticationApi.SHA512))
        assertEquals(0, sshAgentSignatureFlags("ssh-ed25519", SshAuthenticationApi.RIPEMD160))
        assertNull(sshAgentSignatureFlags("ssh-ed25519", -1))
        assertNull(sshAgentSignatureFlags("ssh-ed25519", Int.MAX_VALUE))
    }

    private fun sshBlob(vararg fields: ByteArray): ByteArray =
        ByteArrayOutputStream().use { output ->
            fields.forEach { field ->
                output.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(field.size).array())
                output.write(field)
            }
            output.toByteArray()
        }

    private fun ByteArray.stripPositiveSignByte(): ByteArray =
        if (size > 1 && first() == 0.toByte()) copyOfRange(1, size) else this

    private class WireReader(
        private val bytes: ByteArray,
    ) {
        private var offset = 0
        val remaining: Int get() = bytes.size - offset

        fun read(): ByteArray {
            val length = ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES).int
            offset += Int.SIZE_BYTES
            return bytes.copyOfRange(offset, offset + length)
                .also { offset += length }
        }
    }
}
