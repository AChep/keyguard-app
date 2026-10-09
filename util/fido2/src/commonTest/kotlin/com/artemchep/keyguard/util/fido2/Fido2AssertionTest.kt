package com.artemchep.keyguard.util.fido2

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

class Fido2AssertionTest {
    private val request = Fido2AssertionRequest(
        "vault.example.com", "https://vault.example.com", ByteArray(32),
        listOf(Fido2AllowedCredential(byteArrayOf(7)), Fido2AllowedCredential(byteArrayOf(8))),
        userVerification = Fido2UserVerification.DISCOURAGED,
    )

    @Test
    fun assertionRequestPreservesMultipleCredentialsAndDoesNotRequirePrf() {
        val parsed = parseFido2AssertionRequest(request.webAuthnJson(), request.origin)
        assertEquals(2, parsed.credentials.size)
        assertContentEquals(request.challenge, parsed.challenge)
        assertEquals(Fido2UserVerification.DISCOURAGED, parsed.userVerification)
        val wire = encodeFido2Request(Fido2Operation.Assert(request), "1234")
        assertEquals(3, wire[0].toInt())
        assertContentEquals("1234".encodeToByteArray(), wire.takeLast(4).toByteArray())
    }

    @Test
    fun assertionResponseRejectsTruncationAndTrailingBytes() {
        val response = Fido2AssertionResult(
            byteArrayOf(7), ByteArray(37), byteArrayOf(9), null, false, request.clientDataJson,
        )
        val wire = response.encode()
        val decoded = decodeFido2AssertionResult(wire)
        assertContentEquals(response.clientDataJson, decoded.clientDataJson)
        for (length in wire.indices) assertFails { decodeFido2AssertionResult(wire.copyOf(length)) }
        assertFails { decodeFido2AssertionResult(wire + byteArrayOf(0)) }
        assertFails { decodeFido2AssertionResult(byteArrayOf(0, 0, 0, 2) + wire.drop(4)) }
    }
}
