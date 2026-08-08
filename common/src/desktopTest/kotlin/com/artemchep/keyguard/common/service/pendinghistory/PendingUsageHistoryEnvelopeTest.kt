package com.artemchep.keyguard.common.service.pendinghistory

import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeSshKeyType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

class PendingUsageHistoryEnvelopeTest {
    companion object {
        private const val RSA_KEY_BITS = 2048

        private val keyPair by lazy { generateKeyPair() }
        private val otherKeyPair by lazy { generateKeyPair() }

        private fun generateKeyPair(): Pair<ByteArray, ByteArray> {
            val material = NativeCrypto.ssh.generate(
                type = NativeSshKeyType.RSA,
                rsaBits = RSA_KEY_BITS,
            )
            val description = NativeCrypto.ssh.describe(
                type = NativeSshKeyType.RSA,
                privateKey = material.privateKey,
                publicKey = material.publicKey,
            )
            val export = NativeCrypto.ssh.exportCxf(
                privateKeyPem = description.privateKeyPem,
                publicKeyOpenSsh = description.publicKeyOpenSsh,
            )
            val privateKeyPkcs8 = export.privateKeyPkcs8
            val publicKeySpki = NativeCryptoPrimitives
                .rsaPublicKeySpkiFromPkcs8(privateKeyPkcs8)
            return privateKeyPkcs8 to publicKeySpki
        }
    }

    @Test
    fun `sealed payload opens with the matching private key`() {
        val (privateKey, publicKey) = keyPair
        val plaintext = "pending usage history payload".encodeToByteArray()

        val blob = PendingUsageHistoryEnvelope.seal(publicKey, plaintext)
        val opened = PendingUsageHistoryEnvelope.open(privateKey, blob)

        assertContentEquals(plaintext, opened)
    }

    @Test
    fun `sealing the same payload twice produces different blobs`() {
        val (_, publicKey) = keyPair
        val plaintext = "pending usage history payload".encodeToByteArray()

        val first = PendingUsageHistoryEnvelope.seal(publicKey, plaintext)
        val second = PendingUsageHistoryEnvelope.seal(publicKey, plaintext)

        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `opening with a different private key fails`() {
        val (_, publicKey) = keyPair
        val (otherPrivateKey, _) = otherKeyPair
        val blob = PendingUsageHistoryEnvelope.seal(publicKey, "secret".encodeToByteArray())

        assertFails {
            PendingUsageHistoryEnvelope.open(otherPrivateKey, blob)
        }
    }

    @Test
    fun `tampering with the ciphertext fails authentication`() {
        val (privateKey, publicKey) = keyPair
        val blob = PendingUsageHistoryEnvelope.seal(publicKey, "secret".encodeToByteArray())
        blob[blob.lastIndex] = (blob[blob.lastIndex].toInt() xor 1).toByte()

        assertFails {
            PendingUsageHistoryEnvelope.open(privateKey, blob)
        }
    }

    @Test
    fun `tampering with the wrapped key fails`() {
        val (privateKey, publicKey) = keyPair
        val blob = PendingUsageHistoryEnvelope.seal(publicKey, "secret".encodeToByteArray())
        // The wrapped key starts right after the 3-byte header.
        blob[3] = (blob[3].toInt() xor 1).toByte()

        assertFails {
            PendingUsageHistoryEnvelope.open(privateKey, blob)
        }
    }

    @Test
    fun `truncated and empty blobs are rejected`() {
        val (privateKey, publicKey) = keyPair
        val blob = PendingUsageHistoryEnvelope.seal(publicKey, "secret".encodeToByteArray())

        assertFails {
            PendingUsageHistoryEnvelope.open(privateKey, ByteArray(0))
        }
        assertFails {
            PendingUsageHistoryEnvelope.open(privateKey, blob.copyOfRange(0, 8))
        }
    }

    @Test
    fun `unsupported version is rejected`() {
        val (privateKey, publicKey) = keyPair
        val blob = PendingUsageHistoryEnvelope.seal(publicKey, "secret".encodeToByteArray())
        blob[0] = 2

        assertFails {
            PendingUsageHistoryEnvelope.open(privateKey, blob)
        }
    }
}
