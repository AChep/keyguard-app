package com.artemchep.keyguard.nativecrypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeCryptoX25519Test {
    @Test
    fun rfc7748VectorsAgreeInBothDirections() {
        assertContentEquals(
            expected = hex(SHARED_SECRET),
            actual = NativeCrypto.primitives.x25519(hex(ALICE_PRIVATE_KEY), hex(BOB_PUBLIC_KEY)),
        )
        assertContentEquals(
            expected = hex(SHARED_SECRET),
            actual = NativeCrypto.primitives.x25519(hex(BOB_PRIVATE_KEY), hex(ALICE_PUBLIC_KEY)),
        )
    }

    @Test
    fun noiseKeyPairsAgree() {
        val alice = NativeCrypto.noise.generateKeyPair()
        val bob = NativeCrypto.noise.generateKeyPair()
        try {
            val shared = NativeCrypto.primitives.x25519(alice.privateKey, bob.publicKey)
            assertEquals(32, shared.size)
            assertContentEquals(
                expected = shared,
                actual = NativeCrypto.primitives.x25519(bob.privateKey, alice.publicKey),
            )
        } finally {
            alice.privateKey.fill(0)
            bob.privateKey.fill(0)
        }
    }

    @Test
    fun aLowOrderPublicKeyIsRejected() {
        val failure = assertFailsWith<NativeCryptoException> {
            NativeCrypto.primitives.x25519(hex(ALICE_PRIVATE_KEY), ByteArray(32))
        }
        assertEquals(NativeCryptoErrorCode.AUTHENTICATION_FAILED, failure.code)
    }

    @Test
    fun keysOfTheWrongSizeAreRejected() {
        listOf(0, 31, 33).forEach { size ->
            listOf(
                ByteArray(size) to hex(BOB_PUBLIC_KEY),
                hex(ALICE_PRIVATE_KEY) to ByteArray(size) { 9 },
            ).forEach { (privateKey, publicKey) ->
                val failure = assertFailsWith<NativeCryptoException> {
                    NativeCrypto.primitives.x25519(privateKey, publicKey)
                }
                assertEquals(NativeCryptoErrorCode.INVALID_ARGUMENT, failure.code)
            }
        }
    }

    private fun hex(value: String): ByteArray = value
        .chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

// RFC 7748 section 6.1.
private const val ALICE_PRIVATE_KEY = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"
private const val ALICE_PUBLIC_KEY = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
private const val BOB_PRIVATE_KEY = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"
private const val BOB_PUBLIC_KEY = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
private const val SHARED_SECRET = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
