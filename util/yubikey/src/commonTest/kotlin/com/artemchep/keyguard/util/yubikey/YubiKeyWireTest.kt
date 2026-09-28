package com.artemchep.keyguard.util.yubikey

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class YubiKeyWireTest {
    @Test
    fun provisionEncodesSlotFlagsChallengeAndSecret() {
        val request = YubiKeyOperation.Provision(2, byteArrayOf(9, 0), ByteArray(20) { 7 }, true)
        val wire = encodeYubiKeyRequest(request)
        assertContentEquals(byteArrayOf(3, 2, 3, 2, 9, 0) + ByteArray(20) { 7 }, wire)
    }

    @Test
    fun invalidInputIsRejectedBeforeNativeCall() {
        assertFailsWith<IllegalArgumentException> { YubiKeyOperation.Inspect(0) }
        assertFailsWith<IllegalArgumentException> { YubiKeyOperation.ChallengeResponse(2, ByteArray(65)) }
        assertFailsWith<IllegalArgumentException> { YubiKeyOperation.Provision(2, byteArrayOf(), ByteArray(19), false) }
    }

    @Test
    fun challengeLengthsLeaveRoomForHmacLt64Padding() {
        for (length in listOf(1, 63)) {
            val challenge = ByteArray(length) { 1 }
            val response = YubiKeyOperation.ChallengeResponse(2, challenge)
            val provision = YubiKeyOperation.Provision(2, challenge, ByteArray(20), false)
            assertContentEquals(byteArrayOf(2, 2, 0, length.toByte()) + challenge, encodeYubiKeyRequest(response))
            assertContentEquals(
                byteArrayOf(3, 2, 2, length.toByte()) + challenge + ByteArray(20),
                encodeYubiKeyRequest(provision),
            )
        }
        for (length in listOf(0, 64, 65)) {
            assertFailsWith<IllegalArgumentException> {
                YubiKeyOperation.ChallengeResponse(2, ByteArray(length))
            }
            assertFailsWith<IllegalArgumentException> {
                YubiKeyOperation.Provision(2, ByteArray(length), ByteArray(20), false)
            }
        }
    }

    @Test
    fun responseLengthAndStatusAreValidated() {
        val operation = YubiKeyOperation.ChallengeResponse(2, byteArrayOf(1))
        val error = assertFailsWith<YubiKeyException> { decodeYubiKeyResponse(operation, byteArrayOf(0, 1)) }
        assertEquals(YubiKeyFailure.PROTOCOL, error.failure)
        val rejected = assertFailsWith<YubiKeyException> { decodeYubiKeyResponse(operation, byteArrayOf(7)) }
        assertEquals(YubiKeyFailure.CONFIRMATION_REQUIRED, rejected.failure)
        val response = decodeYubiKeyResponse(operation, byteArrayOf(0) + ByteArray(20) { 8 }) as YubiKeyResult.Response
        assertContentEquals(ByteArray(20) { 8 }, response.bytes)
    }
}
