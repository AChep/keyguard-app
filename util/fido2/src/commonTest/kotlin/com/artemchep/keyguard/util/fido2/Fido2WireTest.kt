package com.artemchep.keyguard.util.fido2

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Fido2WireTest {
    @Test
    fun requestEncodesCredentialAndUtf8PinWithoutTruncation() {
        val credential = ByteArray(300) { it.toByte() }
        val request =
            encodeFido2Request(
                Fido2Operation.Derive(credential, ByteArray(32) { 7 }, ByteArray(32) { 9 }),
                "é123",
            )
        assertContentEquals(byteArrayOf(2, 1, 44, 5), request.copyOfRange(0, 4))
        assertContentEquals(ByteArray(32) { 9 }, request.copyOfRange(4, 36))
        assertContentEquals(ByteArray(32) { 7 }, request.copyOfRange(36, 68))
        assertContentEquals(credential, request.copyOfRange(68, 368))
        assertContentEquals("é123".encodeToByteArray(), request.copyOfRange(368, request.size))
    }

    @Test
    fun canonicallyEquivalentPinsEncodeIdentically() {
        val operation = Fido2Operation.Register(ByteArray(32), ByteArray(32))
        for ((decomposed, composed) in
            listOf("e\u0301123" to "é123", "\u1100\u1161\u11A8123" to "각123")) {
            assertContentEquals(
                encodeFido2Request(operation, composed),
                encodeFido2Request(operation, decomposed),
            )
        }
    }

    @Test
    fun pinLimitUsesNormalizedUtf8Length() {
        val operation = Fido2Operation.Register(ByteArray(32), ByteArray(32))
        // The decomposed input exceeds 63 bytes, but its NFC form is exactly 63 bytes.
        val request = encodeFido2Request(operation, "e\u0301".repeat(31) + "1")
        assertEquals(63, request[3].toInt())
        assertContentEquals(
            encodeFido2Request(operation, "é".repeat(31) + "1"),
            request,
        )
        assertFailsWith<IllegalArgumentException> {
            encodeFido2Request(operation, "e\u0301".repeat(32))
        }
        // U+0344 expands to two combining marks in NFC, exceeding the byte limit.
        assertFailsWith<IllegalArgumentException> {
            encodeFido2Request(operation, "\u0344".repeat(16))
        }
    }

    @Test
    fun pinNormalizationPreservesCompatibilityCharacters() {
        val operation = Fido2Operation.Register(ByteArray(32), ByteArray(32))
        val pin = "①123"
        val pinBytes = pin.encodeToByteArray()
        val request = encodeFido2Request(operation, pin)
        assertContentEquals(pinBytes, request.takeLast(pinBytes.size).toByteArray())
    }

    @Test
    fun pinAndOperationBoundsFailBeforeAccessingHardware() {
        val operation = Fido2Operation.Register(ByteArray(32), ByteArray(32))
        assertFailsWith<IllegalArgumentException> { encodeFido2Request(operation, "a".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { encodeFido2Request(operation, "123\u0000") }
        assertFailsWith<IllegalArgumentException> {
            Fido2Operation.Derive(ByteArray(0), ByteArray(32), ByteArray(32))
        }
        assertFailsWith<IllegalArgumentException> {
            Fido2Operation.Derive(ByteArray(1025), ByteArray(32), ByteArray(32))
        }
    }

    @Test
    fun responseRejectsMissingPrfAndUnknownErrors() {
        val operation = Fido2Operation.Derive(byteArrayOf(1), ByteArray(32), ByteArray(32))
        for (response in
            listOf(byteArrayOf(), byteArrayOf(0), ByteArray(32), ByteArray(34), byteArrayOf(127))) {
            assertEquals(
                Fido2Failure.PROTOCOL,
                assertFailsWith<Fido2Exception> { decodeFido2Response(operation, response) }.failure,
            )
        }
        assertEquals(
            Fido2Failure.PIN_BLOCKED,
            assertFailsWith<Fido2Exception> {
                    decodeFido2Response(
                        operation,
                        byteArrayOf(Fido2Failure.PIN_BLOCKED.code.toByte()),
                    )
                }
                .failure,
        )
        assertContentEquals(
            ByteArray(32) { 42 },
            decodeFido2Response(operation, byteArrayOf(0) + ByteArray(32) { 42 }),
        )
    }
}
