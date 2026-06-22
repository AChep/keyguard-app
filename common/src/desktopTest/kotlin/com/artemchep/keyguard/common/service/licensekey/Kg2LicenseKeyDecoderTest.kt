package com.artemchep.keyguard.common.service.licensekey

import com.artemchep.keyguard.common.service.licensekey.decoder.KeyguardKg2LicensePublicKeys
import com.artemchep.keyguard.common.service.licensekey.decoder.Kg2LicenseKeyDecoder
import com.artemchep.keyguard.common.service.licensekey.decoder.Kg2LicenseProductKind
import com.artemchep.keyguard.common.service.licensekey.decoder.Kg2LicenseSignatureVerifier
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Kg2LicenseKeyDecoderTest {
    private val decoder = Kg2LicenseKeyDecoder(
        signatureVerifier = EcdsaP256Kg2LicenseSignatureVerifier(),
        publicKeysById = testPublicKeysById,
    )
    private val parserOnlyDecoder = Kg2LicenseKeyDecoder(
        signatureVerifier = Kg2LicenseSignatureVerifier { _, _, _ -> true },
        publicKeysById = testPublicKeysById,
    )

    @Test
    fun verifiesKnownSignedFixture() {
        val metadata = assertNotNull(decoder.decodeOrNull(subscriptionToken))

        assertEquals("ABCDEFGHJKLMNPQR", metadata.licenseId)
        assertEquals("premium", metadata.tier)
        assertEquals(Kg2LicenseProductKind.Subscription, metadata.productKind)
        assertEquals("2026-07", metadata.expiryYearMonth)
        assertEquals(KeyguardKg2LicensePublicKeys.CURRENT_KEY_ID, metadata.keyId)
        assertFalse(metadata.isLifetime)
    }

    @Test
    fun acceptsWhitespaceAroundSignedTokens() {
        val metadata = assertNotNull(decoder.decodeOrNull("\n  $subscriptionToken  \t"))

        assertEquals("ABCDEFGHJKLMNPQR", metadata.licenseId)
        assertEquals("2026-07", metadata.expiryYearMonth)
    }

    @Test
    fun rejectsTamperedTokens() {
        assertNull(decoder.decodeOrNull(tamperSignature(subscriptionToken)))
    }

    @Test
    fun mapsLifetimeExpiryMarker() {
        val metadata = assertNotNull(decoder.decodeOrNull(lifetimeToken))

        assertEquals("ABCDEFGHJKLMNPQS", metadata.licenseId)
        assertEquals(Kg2LicenseProductKind.Lifetime, metadata.productKind)
        assertEquals("9999-12", metadata.expiryYearMonth)
        assertTrue(metadata.isLifetime)
    }

    @Test
    fun rejectsMalformedTokenShapes() {
        val parts = subscriptionToken.split(".")
        val malformedTokens = listOf(
            "",
            "KG2A",
            "KG2A.${parts[1]}",
            "KG2A.${parts[1]}.${parts[2]}.extra",
            "KG2B.${parts[1]}.${parts[2]}",
            "KG2A.${parts[1]}*.${parts[2]}",
            "KG2A.${parts[1]}.${parts[2]}=",
            "KG2A..${parts[2]}",
            "KG2A.${parts[1]}.",
            oldJsonKg2Token,
        )

        malformedTokens.forEach { token ->
            assertNull(decoder.decodeOrNull(token), token)
        }
    }

    @Test
    fun rejectsInvalidPayloadSizes() {
        val payload = payloadFromToken(subscriptionToken)

        assertNull(parserOnlyDecoder.decodeOrNull(tokenWithPayload(payload.copyOf(13))))
        assertNull(parserOnlyDecoder.decodeOrNull(tokenWithPayload(payload.copyOf(15))))
    }

    @Test
    fun rejectsInvalidSignatureSizes() {
        val payload = payloadFromToken(subscriptionToken)

        assertNull(parserOnlyDecoder.decodeOrNull(tokenWithPayload(payload, signature = ByteArray(63))))
        assertNull(parserOnlyDecoder.decodeOrNull(tokenWithPayload(payload, signature = ByteArray(65))))
    }

    @Test
    fun rejectsPayloadsWithUnsupportedVersionTierOrKind() {
        assertNull(parserOnlyDecoder.decodeOrNull(tokenWithMutatedPayload { payload ->
            payload[0] = 3.toByte()
        }))
        assertNull(parserOnlyDecoder.decodeOrNull(tokenWithMutatedPayload { payload ->
            payload[11] = 0x01.toByte()
        }))
        assertNull(parserOnlyDecoder.decodeOrNull(tokenWithMutatedPayload { payload ->
            payload[11] = 0x21.toByte()
        }))
        assertNull(parserOnlyDecoder.decodeOrNull(tokenWithMutatedPayload { payload ->
            payload[11] = 0x10.toByte()
        }))
        assertNull(parserOnlyDecoder.decodeOrNull(tokenWithMutatedPayload { payload ->
            payload[11] = 0x13.toByte()
        }))
    }

    @Test
    fun enforcesExpiryMarkerRules() {
        assertNull(parserOnlyDecoder.decodeOrNull(tokenWithMutatedPayload { payload ->
            payload[11] = 0x11.toByte()
            payload.writeUInt16BE(0xffff, offset = 12)
        }))
        assertNull(parserOnlyDecoder.decodeOrNull(tokenWithMutatedPayload { payload ->
            payload[11] = 0x12.toByte()
            payload.writeUInt16BE(0, offset = 12)
        }))
    }

    @Test
    fun decodesMaximumSupportedSubscriptionExpiry() {
        val metadata = assertNotNull(parserOnlyDecoder.decodeOrNull(tokenWithMutatedPayload { payload ->
            payload[11] = 0x11.toByte()
            payload.writeUInt16BE(0xfffe, offset = 12)
        }))

        assertEquals(Kg2LicenseProductKind.Subscription, metadata.productKind)
        assertEquals("7461-03", metadata.expiryYearMonth)
    }

    @Test
    fun rejectsWhenPublicKeyIsMissing() {
        val decoderWithoutKeys = Kg2LicenseKeyDecoder(
            signatureVerifier = Kg2LicenseSignatureVerifier { _, _, _ -> true },
            publicKeysById = emptyMap(),
        )

        assertNull(decoderWithoutKeys.decodeOrNull(subscriptionToken))
    }

    @Test
    fun rejectsWhenSignatureVerifierFails() {
        val rejectingDecoder = Kg2LicenseKeyDecoder(
            signatureVerifier = Kg2LicenseSignatureVerifier { _, _, _ -> false },
        )

        assertNull(rejectingDecoder.decodeOrNull(tokenWithPayload(payloadFromToken(subscriptionToken))))
    }
}

private fun tamperSignature(token: String): String {
    val index = token.lastIndexOf('.') + 1
    val replacement = if (token[index] == 'A') 'B' else 'A'
    return token.substring(0, index) + replacement
}

private fun tokenWithMutatedPayload(
    block: (ByteArray) -> Unit,
): String {
    val payload = payloadFromToken(subscriptionToken)
    block(payload)
    return tokenWithPayload(payload)
}

private fun tokenWithPayload(
    payload: ByteArray,
    signature: ByteArray = fakeSignature,
): String {
    val encodedPayload = base64UrlEncoder.encodeToString(payload)
    val encodedSignature = base64UrlEncoder.encodeToString(signature)
    return "KG2A.$encodedPayload.$encodedSignature"
}

private fun payloadFromToken(token: String): ByteArray =
    base64UrlDecoder.decode(token.split(".")[1])

private fun ByteArray.writeUInt16BE(value: Int, offset: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()
private val base64UrlDecoder = Base64.getUrlDecoder()
private val fakeSignature = ByteArray(64) { index -> index.toByte() }
private val testPublicKeysById = mapOf(
    KeyguardKg2LicensePublicKeys.CURRENT_KEY_ID to """
        -----BEGIN PUBLIC KEY-----
        MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEBXuhYLAnPZVKPoZzu2noW07coT/d
        +ltl2WAU4RsOaeA8FUbfqbo1P4RBPI3YMZ07WW+FTPiP6iJLpVoiYpHegA==
        -----END PUBLIC KEY-----
    """.trimIndent(),
)

private const val subscriptionToken =
    "KG2A.AgBEMhTHQlS2Nc8RAT4" +
            ".YjxsLYGEaqm1sc6ygG-c4OQqM2tC50YzduTLsXd8_yfoFJGpr-lO_E43boe8RgOcd_5HG54UhQZCo2DXjiZ_Xw"

private const val lifetimeToken =
    "KG2A.AgBEMhTHQlS2NdAS__8" +
            ".BeXEYQf63KWXTca_xvHTgQuR1BNqNKA6khJ8S_s8WKgU0ZfKGC8tibGRYGXHroK8RvkUH_A5HacAhTh8NvMSJw"

private val oldJsonKg2Token = listOf(
    "KG2",
    base64UrlEncoder.encodeToString("""{"alg":"ES256","kid":"kg2-p256-v1"}""".encodeToByteArray()),
    base64UrlEncoder.encodeToString(
        """{"v":2,"lid":"ABCDEFGHJKLMNPQR","tier":"premium","kind":"subscription","expYm":"2026-07"}"""
            .encodeToByteArray(),
    ),
    "signature",
).joinToString(".")
