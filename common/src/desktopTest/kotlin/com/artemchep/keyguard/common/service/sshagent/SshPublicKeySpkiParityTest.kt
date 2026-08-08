package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.nativecrypto.NativeCryptoSsh
import com.artemchep.keyguard.nativecrypto.NativeSshKeyType
import com.artemchep.keyguard.nativecrypto.NativeSshSignature
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Differential test for the native SSH public key decoder: the SubjectPublicKeyInfo
 * DER emitted by the Rust core must be byte-identical to the encoding the JDK
 * produces for the same key. The Android SSH authentication IPC service hands
 * this DER to OpenIntents API clients, so any drift is a client-visible break.
 */
@Suppress("FunctionNaming")
class SshPublicKeySpkiParityTest {
    @Test
    fun `native RSA SPKI matches the JDK X509 encoding byte for byte`() {
        repeat(RSA_PARITY_ROUNDS) {
            val keyPair = generateJcaRsaKeyPair()
            val publicKey = keyPair.public as RSAPublicKey

            val decoded = NativeCryptoSsh.decodePublicKey(toOpenSshPublicKey(publicKey))

            assertEquals(NativeSshKeyType.RSA, decoded.type)
            assertEquals(NativeCryptoSsh.ALGORITHM_SSH_RSA, decoded.algorithmName)
            assertContentEquals(publicKey.encoded, decoded.spkiDer)
        }
    }

    @Test
    fun `native Ed25519 SPKI matches the JDK X509 encoding byte for byte`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val jdkSpki = keyPair.public.encoded
        val raw = jdkSpki.copyOfRange(jdkSpki.size - ED25519_RAW_KEY_BYTES, jdkSpki.size)

        val decoded = NativeCryptoSsh.decodePublicKey(toOpenSshEd25519PublicKey(raw))

        assertEquals(NativeSshKeyType.ED25519, decoded.type)
        assertEquals(NativeCryptoSsh.ALGORITHM_SSH_ED25519, decoded.algorithmName)
        assertContentEquals(jdkSpki, decoded.spkiDer)
    }

    @Test
    fun `decoder rejects unsupported algorithms and type-blob mismatches`() {
        val ed25519Body = toOpenSshEd25519PublicKey(ByteArray(ED25519_RAW_KEY_BYTES))
            .substringAfter(' ')
        assertFailsWith<NativeCryptoException> {
            NativeCryptoSsh.decodePublicKey("ssh-rsa $ed25519Body")
        }
        assertFailsWith<NativeCryptoException> {
            NativeCryptoSsh.decodePublicKey("ssh-ed25519 bm90IGEga2V5")
        }
        assertFailsWith<NativeCryptoException> {
            NativeCryptoSsh.decodePublicKey(
                "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTY=",
            )
        }
    }

    @Test
    fun `frameSignature emits the RFC 4253 blob the agent wire uses`() {
        val framed = NativeCryptoSsh.frameSignature(
            NativeSshSignature(
                algorithm = "rsa-sha2-512",
                signature = byteArrayOf(1, 3, 3, 7),
            ),
        )

        val expected = byteArrayOf(
            0, 0, 0, 12,
        ) + "rsa-sha2-512".encodeToByteArray() + byteArrayOf(
            0, 0, 0, 4,
            1, 3, 3, 7,
        )
        assertContentEquals(expected, framed)
    }

    private fun toOpenSshEd25519PublicKey(raw: ByteArray): String {
        val blob = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                val type = "ssh-ed25519".encodeToByteArray()
                output.writeInt(type.size)
                output.write(type)
                output.writeInt(raw.size)
                output.write(raw)
            }
            bytes.toByteArray()
        }
        return "ssh-ed25519 ${Base64.getEncoder().encodeToString(blob)}"
    }

    private companion object {
        private const val ED25519_RAW_KEY_BYTES = 32
        private const val RSA_PARITY_ROUNDS = 3
    }
}
