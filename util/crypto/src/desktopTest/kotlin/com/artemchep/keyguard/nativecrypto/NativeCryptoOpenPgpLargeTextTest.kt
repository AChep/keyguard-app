package com.artemchep.keyguard.nativecrypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeCryptoOpenPgpLargeTextTest {
    @Test
    fun armoredEncryptionStreamsWhenTheResultExceedsTheControlEnvelope() {
        val material =
            NativeCrypto.openPgp.generateKey(
                kind = NativeOpenPgpKeyKind.LEGACY_ED25519_X25519,
                userId = "Native crypto large text <large-text@test.invalid>",
                creationTimeEpochSeconds = 1_700_000_000L,
            )
        val plaintext = deterministicIncompressibleBytes(13 * 1024 * 1024)
        var encrypted: NativeOpenPgpEncryptResult? = null
        var decrypted: ByteArray? = null
        try {
            encrypted =
                NativeCrypto.openPgp.encrypt(
                    content = plaintext,
                    publicKeys = listOf(material.publicKeyArmored),
                    fileName = "large-text.txt",
                    armored = true,
                    literalTimeEpochSeconds = 1_700_000_001L,
                    referenceTimeEpochSeconds = 1_700_000_001L,
                )
            assertTrue(encrypted.data.size > NativeCrypto.MAX_CONTROL_ENVELOPE_BYTES)

            decrypted =
                NativeCrypto.openPgp.decrypt(
                    content = encrypted.data,
                    privateKeys = listOf(material.privateKeyArmored),
                    referenceTimeEpochSeconds = 1_700_000_002L,
                )
                    .data
            assertContentEquals(plaintext, checkNotNull(decrypted))
        } finally {
            plaintext.fill(0)
            encrypted?.data?.fill(0)
            decrypted?.fill(0)
            material.privateKeyArmored.fill(0)
            material.publicKeyArmored.fill(0)
        }
    }

    @Test
    fun inMemoryFacadeRoundTripsThePlaintextLimit() {
        val material = generateMaterial("boundary")
        val plaintext = ByteArray(NativeCryptoOpenPgp.MAX_IN_MEMORY_PLAINTEXT_BYTES) { 0x41 }
        var encrypted: NativeOpenPgpEncryptResult? = null
        var decrypted: ByteArray? = null
        try {
            encrypted = NativeCrypto.openPgp.encrypt(
                content = plaintext,
                publicKeys = listOf(material.publicKeyArmored),
                fileName = "boundary.txt",
                armored = false,
                literalTimeEpochSeconds = 1_700_000_001L,
                referenceTimeEpochSeconds = 1_700_000_001L,
            )
            decrypted = NativeCrypto.openPgp.decrypt(
                content = encrypted.data,
                privateKeys = listOf(material.privateKeyArmored),
                referenceTimeEpochSeconds = 1_700_000_002L,
            ).data

            assertContentEquals(plaintext, checkNotNull(decrypted))
        } finally {
            plaintext.fill(0)
            encrypted?.data?.fill(0)
            decrypted?.fill(0)
            material.privateKeyArmored.fill(0)
            material.publicKeyArmored.fill(0)
        }
    }

    @Test
    fun streamingCiphertextThatExpandsPastThePlaintextLimitIsRejected() {
        val material = generateMaterial("expansion")
        val plaintext = ByteArray(NativeCryptoOpenPgp.MAX_IN_MEMORY_PLAINTEXT_BYTES + 1) { 0x41 }
        var encrypted: ByteArray? = null
        try {
            val ciphertext = encryptStreaming(
                plaintext = plaintext,
                publicKey = material.publicKeyArmored,
                fileName = "expansion.txt",
            )
            encrypted = ciphertext

            val failure = assertFailsWith<NativeCryptoException> {
                NativeCrypto.openPgp.decrypt(
                    content = ciphertext,
                    privateKeys = listOf(material.privateKeyArmored),
                    referenceTimeEpochSeconds = 1_700_000_002L,
                )
            }

            assertEquals(NativeCryptoErrorCode.RESOURCE_LIMIT, failure.code)
        } finally {
            plaintext.fill(0)
            encrypted?.fill(0)
            material.privateKeyArmored.fill(0)
            material.publicKeyArmored.fill(0)
        }
    }

    @Test
    fun truncatedCiphertextDoesNotReturnProvisionalPlaintext() {
        val material = generateMaterial("truncated")
        val plaintext = deterministicIncompressibleBytes(512 * 1024)
        var encrypted: NativeOpenPgpEncryptResult? = null
        var truncated: ByteArray? = null
        try {
            encrypted = NativeCrypto.openPgp.encrypt(
                content = plaintext,
                publicKeys = listOf(material.publicKeyArmored),
                fileName = "truncated.bin",
                armored = false,
                literalTimeEpochSeconds = 1_700_000_001L,
                referenceTimeEpochSeconds = 1_700_000_001L,
            )
            val truncatedCiphertext = encrypted.data.copyOf(encrypted.data.size - 1)
            truncated = truncatedCiphertext

            val failure = assertFailsWith<NativeCryptoException> {
                NativeCrypto.openPgp.decrypt(
                    content = truncatedCiphertext,
                    privateKeys = listOf(material.privateKeyArmored),
                    referenceTimeEpochSeconds = 1_700_000_002L,
                )
            }

            assertEquals(NativeCryptoErrorCode.AUTHENTICATION_FAILED, failure.code)
        } finally {
            plaintext.fill(0)
            encrypted?.data?.fill(0)
            truncated?.fill(0)
            material.privateKeyArmored.fill(0)
            material.publicKeyArmored.fill(0)
        }
    }

    private fun encryptStreaming(
        plaintext: ByteArray,
        publicKey: ByteArray,
        fileName: String,
    ): ByteArray {
        val outputs = mutableListOf<ByteArray>()
        var totalSize = 0
        return try {
            NativeCrypto.openPgp
                .openEncryption(
                    publicKeys = listOf(publicKey),
                    fileName = fileName,
                    armored = false,
                    literalTimeEpochSeconds = 1_700_000_001L,
                    referenceTimeEpochSeconds = 1_700_000_001L,
                ).use { session ->
                    var offset = 0
                    while (offset < plaintext.size) {
                        val length = minOf(NATIVE_CRYPTO_STREAM_CHUNK_BYTES, plaintext.size - offset)
                        session.update(plaintext, offset, length).also { output ->
                            outputs += output
                            totalSize += output.size
                        }
                        offset += length
                    }
                    session.finish().data.also { output ->
                        outputs += output
                        totalSize += output.size
                    }
                }

            ByteArray(totalSize).also { plaintext ->
                var offset = 0
                outputs.forEach { output ->
                    output.copyInto(plaintext, destinationOffset = offset)
                    offset += output.size
                }
            }
        } finally {
            outputs.forEach { output -> output.fill(0) }
        }
    }

    private fun generateMaterial(label: String): NativeOpenPgpKeyMaterial =
        NativeCrypto.openPgp.generateKey(
            kind = NativeOpenPgpKeyKind.LEGACY_ED25519_X25519,
            userId = "Native crypto $label <$label@test.invalid>",
            creationTimeEpochSeconds = 1_700_000_000L,
        )

    private fun deterministicIncompressibleBytes(size: Int): ByteArray {
        var state = 0x6d2b79f5
        return ByteArray(size) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            state.toByte()
        }
    }
}
