package com.artemchep.keyguard.nativecrypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Shared by Kotlin/JS and Kotlin/WasmGC; streaming parity stays in threadedTest. */
class NativeCryptoWebOpenPgpTest {
    @Test
    fun advertisesBoundedOpenPgpWithoutThreadedSessions() {
        NativeCrypto.ensureReady()
        assertTrue(NativeCryptoCapability.OPENPGP_READ in NativeCrypto.capabilities)
        assertTrue(NativeCryptoCapability.OPENPGP_WRITE in NativeCrypto.capabilities)
        assertFalse(NativeCryptoCapability.OPENPGP_DETACHED_VERIFY_STREAMING in NativeCrypto.capabilities)
        assertFalse(NativeCryptoCapability.OPENPGP_MESSAGE_STREAMING in NativeCrypto.capabilities)
        assertFalse(NativeCryptoCapability.OPENPGP_STREAM_DRAIN in NativeCrypto.capabilities)
    }

    @Test
    fun publicStreamingOperationsFailExplicitly() {
        assertUnsupported("open_pgp_detached_verify.stream_open") {
            NativeCrypto.openPgp.openDetachedVerification(byteArrayOf(), emptyList())
        }
        assertUnsupported("open_pgp_encrypt.stream_open") {
            NativeCrypto.openPgp.openEncryption(
                publicKeys = emptyList(),
                candidateRevocationKeys = emptyList(),
                fileName = "",
                armored = false,
            )
        }
        assertUnsupported("open_pgp_decrypt.stream_open") {
            NativeCrypto.openPgp.openDecryption(privateKeys = emptyList())
        }
    }

    @Test
    fun rustDispatcherAlsoRejectsUnsupportedStreams() {
        // Bypass the public capability guards to verify the Rust/C ABI contract.
        val operations = listOf(
            "open_pgp_detached_verify.stream_open" to OpenPgpDetachedVerifyStreamOpenOperationProto(
                OpenPgpDetachedVerifyStreamOpenRequestProto(byteArrayOf(), emptyList()),
            ),
            "open_pgp_encrypt.stream_open" to OpenPgpEncryptStreamOpenOperationProto(
                OpenPgpEncryptStreamOpenRequestProto(
                    publicKeys = emptyList(),
                    candidateRevocationKeys = emptyList(),
                    fileName = "",
                    armored = false,
                ),
            ),
            "open_pgp_decrypt.stream_open" to OpenPgpDecryptStreamOpenOperationProto(
                OpenPgpDecryptStreamOpenRequestProto(emptyList(), emptyList()),
            ),
        )
        operations.forEach { (operation, request) ->
            assertUnsupported(operation) { NativeCrypto.openHandle(operation, request) }
        }
        assertUnsupported("open_pgp_stream_drain") {
            NativeCrypto.call(
                "open_pgp_stream_drain",
                OpenPgpStreamDrainOperationProto(OpenPgpStreamDrainRequestProto(1L)),
            )
        }
    }

    @Test
    fun oversizedMessagesFailWithResourceLimit() {
        val encryptionFailure = assertFailsWith<NativeCryptoException> {
            NativeCrypto.openPgp.encrypt(
                content = ByteArray(OPEN_PGP_ONE_SHOT_MAX_CONTENT_BYTES + 1),
                publicKeys = listOf(byteArrayOf(1)),
                candidateRevocationKeys = emptyList(),
                fileName = "",
                armored = false,
            )
        }
        assertEquals(NativeCryptoErrorCode.RESOURCE_LIMIT, encryptionFailure.code)
        assertEquals("open_pgp_encrypt", encryptionFailure.operation)

        val decryptionFailure = assertFailsWith<NativeCryptoException> {
            NativeCrypto.openPgp.decrypt(
                content = ByteArray(NativeCrypto.MAX_CONTROL_ENVELOPE_BYTES + 1),
                privateKeys = listOf(byteArrayOf(1)),
            )
        }
        assertEquals(NativeCryptoErrorCode.RESOURCE_LIMIT, decryptionFailure.code)
        assertEquals("open_pgp_decrypt", decryptionFailure.operation)
    }

    @Test
    fun compressedInputCannotExceedThePlaintextLimit() {
        val material = generateOpenPgpTestMaterial(NativeOpenPgpKeyVersion.V4)
        val plaintext = ByteArray(NativeCryptoOpenPgp.MAX_IN_MEMORY_PLAINTEXT_BYTES + 1)
        try {
            // The raw protocol accepts this bounded plaintext; the public facade
            // must still reject its expanded size when decrypting the small message.
            val payload = NativeCrypto.call(
                "open_pgp_encrypt",
                OpenPgpEncryptOperationProto(
                    OpenPgpEncryptRequestProto(
                        content = plaintext,
                        publicKeys = listOf(material.publicKeyArmored),
                        candidateRevocationKeys = emptyList(),
                        fileName = "",
                        armored = false,
                        enableCompression = true,
                    ),
                ),
            ).requireBytes("open_pgp_encrypt")
            val encrypted = decodeNativeCryptoPayload<OpenPgpEncryptResultProto>("open_pgp_encrypt", payload).data
            try {
                assertTrue(encrypted.size < OPEN_PGP_ONE_SHOT_MAX_CONTENT_BYTES)
                val failure = assertFailsWith<NativeCryptoException> {
                    NativeCrypto.openPgp.decrypt(encrypted, listOf(material.privateKeyArmored))
                }
                assertEquals(NativeCryptoErrorCode.RESOURCE_LIMIT, failure.code)
            } finally {
                encrypted.fill(0)
            }
        } finally {
            plaintext.fill(0)
            material.privateKeyArmored.fill(0)
            material.publicKeyArmored.fill(0)
        }
    }

    @Test
    fun maximumBoundedPlaintextStillDecryptsWithoutStreaming() {
        val material = generateOpenPgpTestMaterial(NativeOpenPgpKeyVersion.V4)
        val plaintext = ByteArray(OPEN_PGP_ONE_SHOT_MAX_CONTENT_BYTES) { it.toByte() }
        try {
            val encrypted = NativeCrypto.openPgp.encrypt(
                content = plaintext,
                publicKeys = listOf(material.publicKeyArmored),
                candidateRevocationKeys = emptyList(),
                fileName = "boundary.bin",
                armored = false,
                enableCompression = false,
            )
            try {
                // Packet framing makes ciphertext larger than the plaintext limit.
                assertTrue(encrypted.data.size > OPEN_PGP_ONE_SHOT_MAX_CONTENT_BYTES)
                val decrypted = NativeCrypto.openPgp.decrypt(encrypted.data, listOf(material.privateKeyArmored))
                try {
                    assertContentEquals(plaintext, decrypted.data)
                } finally {
                    decrypted.data.fill(0)
                }
            } finally {
                encrypted.data.fill(0)
            }
        } finally {
            plaintext.fill(0)
            material.privateKeyArmored.fill(0)
            material.publicKeyArmored.fill(0)
        }
    }

    private fun assertUnsupported(operation: String, block: () -> Unit) {
        val failure = assertFailsWith<NativeCryptoException>(block = block)
        assertEquals(NativeCryptoErrorCode.UNSUPPORTED_OPERATION, failure.code)
        assertEquals(operation, failure.operation)
    }
}
