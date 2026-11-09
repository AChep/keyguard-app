package com.artemchep.keyguard.nativecrypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeCryptoHybridTest {
    @Test
    fun trafficMatchesIndependentAesGcmVectorAcrossBridge() {
        val key = ByteArray(32) { 5 }
        val expected =
            hex(
                "08ef569edd58ca5ccf2002716ddeac8f3fa5b1b78337c1d0aeef272715ca2afe" +
                    "0c9f7cbc3a3c5b054df9191f73ea81e1",
            )
        val plaintext = byteArrayOf(1, 2, 3)
        assertContentEquals(expected, NativeCryptoHybrid.encrypt(key, 0, plaintext))
        assertContentEquals(plaintext, NativeCryptoHybrid.decrypt(key, 0, expected))
        val error =
            assertFailsWith<NativeCryptoException> {
                NativeCryptoHybrid.decrypt(key, 1, expected)
            }
        assertEquals(NativeCryptoErrorCode.AUTHENTICATION_FAILED, error.code)
    }

    @Test
    fun handshakeCrossesBridgeAndRejectsTamperedProof() {
        val identity = hex("026ff03b949241ce1dadd43519e6960e0a85b41a69a05c328103aa2bce1594ca16")
        val message =
            hex(
                "04550f471003f3df97c3df506ac797f6721fb1a1fb7b8f6f83d224498a65c88e241" +
                    "36093d7012e509a73715cbd0b00a3cc0ff4b5c01b3ffa196ab1fb327036b8e6d" +
                    "abb175195350d7bac1c1a654d6b4871",
            )
        val psk = ByteArray(32) { 4 }
        val response = NativeCryptoHybrid.respond(identity, psk, message)
        assertEquals(145, response.size)
        response.fill(0)
        message[80] = (message[80].toInt() xor 1).toByte()
        assertFailsWith<NativeCryptoException> { NativeCryptoHybrid.respond(identity, psk, message) }
    }

    private fun hex(value: String): ByteArray = value.hexToByteArray()
}
