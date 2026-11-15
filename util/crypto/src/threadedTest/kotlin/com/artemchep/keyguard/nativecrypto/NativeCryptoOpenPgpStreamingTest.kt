package com.artemchep.keyguard.nativecrypto

import com.artemchep.keyguard.nativecrypto.NativeCryptoOpenPgpFixtureTest.Companion.DETACHED_BODY
import com.artemchep.keyguard.nativecrypto.NativeCryptoOpenPgpFixtureTest.Companion.DETACHED_SIGNATURE
import com.artemchep.keyguard.nativecrypto.NativeCryptoOpenPgpFixtureTest.Companion.PUBLIC_KEY
import com.artemchep.keyguard.nativecrypto.NativeCryptoOpenPgpFixtureTest.Companion.REFERENCE_TIME
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Thread-dependent parity checks; web tests separately verify unsupported operations. */
class NativeCryptoOpenPgpStreamingTest {
    @Test
    fun advertisesThreadedOpenPgpCapabilities() {
        assertTrue(NativeCryptoCapability.OPENPGP_DETACHED_VERIFY_STREAMING in NativeCrypto.capabilities)
        assertTrue(NativeCryptoCapability.OPENPGP_MESSAGE_STREAMING in NativeCrypto.capabilities)
        assertTrue(NativeCryptoCapability.OPENPGP_STREAM_DRAIN in NativeCrypto.capabilities)
    }

    @Test
    fun detachedVerificationStreamsAcrossArbitraryChunkBoundaries() {
        for (chunkSize in listOf(1, 7, 31, 64 * 1024)) {
            val session = NativeCrypto.openPgp.openDetachedVerification(
                signature = DETACHED_SIGNATURE.encodeToByteArray(),
                publicKeys = listOf(PUBLIC_KEY.encodeToByteArray()),
                referenceTimeEpochSeconds = REFERENCE_TIME,
            )
            session.use {
                val body = DETACHED_BODY.encodeToByteArray()
                var offset = 0
                while (offset < body.size) {
                    val length = minOf(chunkSize, body.size - offset)
                    it.update(body, offset = offset, length = length)
                    offset += length
                }
                assertOpenPgpFixtureVerification(it.finish(), createdAtEpochSeconds = 1_784_073_600L)
            }
        }
    }

    @Test
    fun streamedTamperIsRejectedAtFinishAndConsumesTheSession() {
        val session = NativeCrypto.openPgp.openDetachedVerification(
            signature = DETACHED_SIGNATURE.encodeToByteArray(),
            publicKeys = listOf(PUBLIC_KEY.encodeToByteArray()),
            referenceTimeEpochSeconds = REFERENCE_TIME,
        )
        session.use {
            val tampered = DETACHED_BODY.encodeToByteArray().also { body ->
                body[0] = 'X'.code.toByte()
            }
            it.update(tampered)
            val verification = it.finish()
            assertEquals(NativeOpenPgpVerificationStatus.INVALID, verification.status)

            val finishReuse = assertFailsWith<NativeCryptoException> { it.finish() }
            assertEquals(NativeCryptoErrorCode.INVALID_SESSION, finishReuse.code)
            val updateReuse = assertFailsWith<NativeCryptoException> { it.update(byteArrayOf(0)) }
            assertEquals(NativeCryptoErrorCode.INVALID_SESSION, updateReuse.code)
        }
    }

    @Test
    fun closingAPartialVerificationSessionCancelsItAndIsIdempotent() {
        val session = NativeCrypto.openPgp.openDetachedVerification(
            signature = DETACHED_SIGNATURE.encodeToByteArray(),
            publicKeys = listOf(PUBLIC_KEY.encodeToByteArray()),
            referenceTimeEpochSeconds = REFERENCE_TIME,
        )
        session.update(DETACHED_BODY.encodeToByteArray(), length = 7)

        session.close()
        session.close()

        val updateReuse = assertFailsWith<NativeCryptoException> { session.update(byteArrayOf(0)) }
        assertEquals(NativeCryptoErrorCode.INVALID_SESSION, updateReuse.code)
        val finishReuse = assertFailsWith<NativeCryptoException> { session.finish() }
        assertEquals(NativeCryptoErrorCode.INVALID_SESSION, finishReuse.code)
    }

    @Test
    fun signedOcbEncryptionAuthenticatesAfterStreamingRoundTrip() = withOpenPgpTestMaterial { material ->
        val plaintext = "native signed encryption".encodeToByteArray()
        val encryptedChunks = mutableListOf<ByteArray>()
        NativeCrypto.openPgp.openEncryption(
            publicKeys = listOf(material.publicKeyArmored),
            candidateRevocationKeys = emptyList(),
            signingPrivateKey = material.privateKeyArmored,
            fileName = "probe.txt",
            armored = false,
        ).use { session ->
            encryptedChunks += session.update(plaintext, offset = 0, length = 3)
            encryptedChunks += session.update(plaintext, offset = 3, length = plaintext.size - 3)
            val final = session.finish()
            assertEquals(
                if (material.fingerprint.length == 64) NativeOpenPgpProtectionMode.SEIPD_V2_AEAD
                else NativeOpenPgpProtectionMode.GNUPG_OCB,
                final.protectionMode,
            )
            encryptedChunks += final.data
        }
        val ciphertext = encryptedChunks.fold(byteArrayOf()) { result, chunk -> result + chunk }
        val decryptedChunks = mutableListOf<ByteArray>()
        NativeCrypto.openPgp.openDecryption(
            privateKeys = listOf(material.privateKeyArmored),
            verificationPublicKeys = listOf(material.publicKeyArmored),
        ).use { session ->
            val split = ciphertext.size / 2
            decryptedChunks += session.update(ciphertext, offset = 0, length = split)
            decryptedChunks += session.update(ciphertext, offset = split, length = ciphertext.size - split)
            val final = session.finish()
            assertEquals(NativeOpenPgpVerificationStatus.VALID, final.verification?.status)
            decryptedChunks += final.data
        }
        assertContentEquals(plaintext, decryptedChunks.fold(byteArrayOf()) { result, chunk -> result + chunk })
    }
}
