package com.artemchep.keyguard.util.fidohybrid

import com.artemchep.keyguard.util.webauthn.PasskeyCredentialId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HybridProtocolTest {
    @Test
    fun qrAcceptsBooleanAndUnknownFieldsAndClearsMaterial() {
        // Independently packed decimal encoding, including boolean field 4 and GREASE key 65535.
        val qr =
            HybridQr.parse(
                "FIDO:/6767604935059062948727942671537211028307910354016841417559286341" +
                    "3381416822717931856650056405080729773802542101049181187005795029762731" +
                    "6200281471117231106",
            )
        assertContentEquals(hex("026ff03b949241ce1dadd43519e6960e0a85b41a69a05c328103aa2bce1594ca16"), qr.peerIdentity)
        assertContentEquals(ByteArray(16) { it.toByte() }, qr.secret)
        assertFalse(qr.toString().contains("FIDO"))
        qr.clear()
        assertTrue(qr.secret.all { it == 0.toByte() })
    }

    @Test
    fun qrRejectsOverflowNonAsciiDigitsAndOversizedInputs() {
        for (value in listOf(
            "FIDO:/99999999999999999",
            "FIDO:/１２３",
            "FIDO:/1",
            "FIDO:/" + "0".repeat(2048),
        )) {
            assertFails { HybridQr.parse(value) }
        }
    }

    @Test
    fun cborRejectsDuplicateKeysTrailingDataBadUtf8AndExcessiveDepth() {
        assertFailsWith<IllegalArgumentException> { HybridCbor.decode(hex("61ff")) }
        for (bytes in listOf(
            hex("a201000101"),
            hex("0000"),
            hex("61ff"),
            hex("9f00ff"),
            hex("5affffffff"),
            ByteArray(10) { 0x81.toByte() } + byteArrayOf(0),
        )) {
            assertFails { HybridCbor.decode(bytes) }
        }
        assertContentEquals(hex("a261610162626202"), HybridCbor.encode(mapOf("bb" to 2, "a" to 1)))
    }

    @Test
    fun assertionPreservesAllowListTypesAndRequiresVerification() {
        val request =
            parseAssertion(
                assertion(
                    mapOf(
                        3 to listOf(mapOf("type" to "unknown", "id" to byteArrayOf(5))),
                    ),
                ),
            )
        assertEquals("example.com", request.rpId)
        assertEquals("required", request.userVerification)
        assertTrue(request.allowedCredentials.isAllowCredentialsSupplied)
        assertEquals(
            "unknown",
            request.allowedCredentials.descriptors
                .single()
                .type,
        )
        assertEquals(
            PasskeyCredentialId.decode(byteArrayOf(5)),
            request.allowedCredentials.descriptors
                .single()
                .credentialId,
        )
        val discoverable = parseAssertion(assertion(mapOf(3 to emptyList<Any>())))
        assertFalse(discoverable.allowedCredentials.isAllowCredentialsSupplied)
    }

    @Test
    fun malformedHashesAndUnsupportedExtensionsCannotReachSigner() {
        for (extra in listOf(
            mapOf(2 to ByteArray(31)),
            mapOf(1 to "other.example/"),
            mapOf(4 to mapOf("hmac-secret" to true)),
            mapOf(5 to mapOf("rk" to false)),
            mapOf(3 to listOf(mapOf("type" to "public-key", "id" to "not bytes"))),
        )) {
            assertFails { parseAssertion(assertion(extra)) }
        }
    }

    @Test
    fun authenticatorOnlyAdvertisesImplementedCapabilities() {
        val info = HybridCbor.decode(HybridCtap.getInfo(ByteArray(16))) as Map<*, *>
        assertEquals(listOf("FIDO_2_0"), info[1L])
        assertEquals(listOf("hmac-secret", "hmac-secret-mc"), info[2L])
        assertEquals(listOf(1L, 2L), info[6L])
        assertEquals(mapOf("rk" to true, "up" to true, "uv" to true), info[4L])
        val post = HybridCbor.decode(HybridCtap.postHandshake(ByteArray(16))) as Map<*, *>
        assertContentEquals(HybridCtap.getInfo(ByteArray(16)), post[1L] as ByteArray)
    }

    private fun parseAssertion(command: ByteArray) = HybridCtap.assertionOptions(HybridCtap.parameters(command), null)

    private fun assertion(extra: Map<Int, Any>): ByteArray =
        byteArrayOf(2) +
            HybridCbor.encode(mapOf(1 to "example.com", 2 to ByteArray(32)) + extra)

    private fun hex(value: String): ByteArray = value.hexToByteArray()
}
