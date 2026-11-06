package com.artemchep.keyguard.nativecrypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeCryptoEd25519Test {
    private class Vector(
        val privateKey: String,
        val publicKey: String,
        val message: String,
        val signature: String,
    )

    /** RFC 8032 section 7.1, TEST 1 to TEST 3. */
    private val vectors = listOf(
        Vector(
            privateKey = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
            publicKey = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            message = "",
            signature = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        ),
        Vector(
            privateKey = "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
            publicKey = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
            message = "72",
            signature = "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
        ),
        Vector(
            privateKey = "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
            publicKey = "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
            message = "af82",
            signature = "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac" +
                "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
        ),
    )

    @Test
    fun rfc8032VectorsSignAndVerifyThroughTheRealNativeLibrary() {
        vectors.forEach { vector ->
            assertContentEquals(
                expected = hex(vector.signature),
                actual = NativeCrypto.primitives.ed25519Sign(
                    privateKey = hex(vector.privateKey),
                    data = hex(vector.message),
                ),
            )
            assertTrue(
                NativeCrypto.primitives.ed25519Verify(
                    publicKey = hex(vector.publicKey),
                    data = hex(vector.message),
                    signature = hex(vector.signature),
                ),
            )
        }
    }

    @Test
    fun tamperedSignaturesMessagesAndKeysDoNotVerify() {
        val vector = vectors[1]
        val tamperedSignature = hex(vector.signature).also { it[0] = (it[0].toInt() xor 1).toByte() }
        val tamperedScalar = hex(vector.signature).also { it[63] = (it[63].toInt() xor 0x40).toByte() }
        listOf(
            Triple(hex(vector.publicKey), hex(vector.message), tamperedSignature),
            Triple(hex(vector.publicKey), hex(vector.message), tamperedScalar),
            Triple(hex(vector.publicKey), "s".encodeToByteArray(), hex(vector.signature)),
            Triple(hex(vectors[0].publicKey), hex(vector.message), hex(vector.signature)),
            Triple(hex(vector.publicKey), hex(vector.message), hex(vector.signature).copyOf(63)),
            Triple(hex(vector.publicKey), hex(vector.message), hex(vector.signature).copyOf(65)),
        ).forEach { (publicKey, data, signature) ->
            assertFalse(NativeCrypto.primitives.ed25519Verify(publicKey, data, signature))
        }
    }

    @Test
    fun generatedKeyPairsSignAndVerify() {
        val first = NativeCrypto.primitives.ed25519Generate()
        val second = NativeCrypto.primitives.ed25519Generate()
        try {
            assertEquals(32, first.privateKey.size)
            assertEquals(32, first.publicKey.size)
            assertFalse(first.privateKey.contentEquals(second.privateKey))
            assertFalse(first.publicKey.contentEquals(second.publicKey))

            val data = "roster entry".encodeToByteArray()
            val signature = NativeCrypto.primitives.ed25519Sign(first.privateKey, data)
            assertEquals(64, signature.size)
            assertContentEquals(
                expected = signature,
                actual = NativeCrypto.primitives.ed25519Sign(first.privateKey, data),
            )
            assertTrue(NativeCrypto.primitives.ed25519Verify(first.publicKey, data, signature))
            assertFalse(NativeCrypto.primitives.ed25519Verify(second.publicKey, data, signature))
        } finally {
            first.privateKey.fill(0)
            second.privateKey.fill(0)
        }
    }

    @Test
    fun keysOfTheWrongLengthAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            NativeCrypto.primitives.ed25519Sign(ByteArray(31), byteArrayOf())
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCrypto.primitives.ed25519Sign(ByteArray(64), byteArrayOf())
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCrypto.primitives.ed25519Verify(ByteArray(33), byteArrayOf(), ByteArray(64))
        }
    }

    private fun hex(value: String): ByteArray = value
        .chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
