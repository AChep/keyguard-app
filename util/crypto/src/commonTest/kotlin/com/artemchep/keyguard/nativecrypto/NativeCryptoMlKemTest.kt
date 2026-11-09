package com.artemchep.keyguard.nativecrypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class NativeCryptoMlKemTest {
    @Test
    fun aSecretRoundTrips() {
        val pair = NativeCrypto.primitives.mlKemGenerate()
        assertEquals(3168, pair.privateKey.size)
        assertEquals(1568, pair.publicKey.size)
        val sent = NativeCrypto.primitives.mlKemEncapsulate(pair.publicKey)
        assertEquals(1568, sent.ciphertext.size)
        assertEquals(32, sent.sharedSecret.size)
        assertContentEquals(
            expected = sent.sharedSecret,
            actual = NativeCrypto.primitives.mlKemDecapsulate(pair.privateKey, sent.ciphertext),
        )
    }

    @Test
    fun aForgedCiphertextYieldsAnotherSecret() {
        val pair = NativeCrypto.primitives.mlKemGenerate()
        val sent = NativeCrypto.primitives.mlKemEncapsulate(pair.publicKey)
        val forged = sent.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val received = NativeCrypto.primitives.mlKemDecapsulate(pair.privateKey, forged)
        assertFalse(received.contentEquals(sent.sharedSecret))
    }

    @Test
    fun malformedInputsAreRejected() {
        val pair = NativeCrypto.primitives.mlKemGenerate()
        val ciphertext = NativeCrypto.primitives.mlKemEncapsulate(pair.publicKey).ciphertext
        listOf(
            { NativeCrypto.primitives.mlKemEncapsulate(pair.publicKey.copyOf(1567)) },
            // The coefficients of this key are not reduced modulo q.
            { NativeCrypto.primitives.mlKemEncapsulate(ByteArray(1568) { -1 }) },
            { NativeCrypto.primitives.mlKemDecapsulate(pair.privateKey.copyOf(3167), ciphertext) },
            { NativeCrypto.primitives.mlKemDecapsulate(pair.privateKey, ciphertext.copyOf(1567)) },
        ).forEach { call ->
            val failure = assertFailsWith<NativeCryptoException> { call() }
            assertEquals(NativeCryptoErrorCode.INVALID_ARGUMENT, failure.code)
        }
    }
}
