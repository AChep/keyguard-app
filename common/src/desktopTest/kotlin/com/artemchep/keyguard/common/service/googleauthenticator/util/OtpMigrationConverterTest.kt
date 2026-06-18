package com.artemchep.keyguard.common.service.googleauthenticator.util

import arrow.core.Either
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.service.googleauthenticator.model.OtpAuthMigrationData.OtpParameters
import com.artemchep.keyguard.common.service.googleauthenticator.model.OtpAuthMigrationData.OtpParameters.Algorithm
import com.artemchep.keyguard.common.service.googleauthenticator.model.OtpAuthMigrationData.OtpParameters.DigitCount
import com.artemchep.keyguard.common.service.googleauthenticator.model.OtpAuthMigrationData.OtpParameters.Type
import com.artemchep.keyguard.copy.Base32ServiceJvm
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Characterizes the `otpauth://` URIs emitted by [build] when converting a
 * Google Authenticator migration payload, validated against the Key Uri Format
 * (https://github.com/google/google-authenticator/wiki/Key-Uri-Format).
 */
class OtpMigrationConverterTest {
    @Test
    fun `totp secret omits base32 padding`() {
        // An 11-byte secret would otherwise base32-encode with '======' padding
        // and leak into the uri as '%3D%3D…'.
        val uri = params(secret = ByteArray(11) { it.toByte() }).uri()

        assertFalse(uri.contains("%3D"), "Base32 padding leaked into uri: $uri")
        assertEquals("AAAQEAYEAUDAOCAJBI", Url(uri).parameters["secret"])
    }

    @Test
    fun `totp keeps an already-unpadded secret intact`() {
        // A 20-byte secret base32-encodes to exactly 32 chars with no padding.
        val uri = params(secret = ByteArray(20) { it.toByte() }).uri()

        assertEquals("AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQT", Url(uri).parameters["secret"])
    }

    @Test
    fun `totp emits an issuer parameter consistent with the label`() {
        val uri = params(issuer = "Example Co", name = "alice@example.com").uri()

        assertEquals("Example Co", Url(uri).parameters["issuer"])
        assertEquals("Example%20Co:alice@example.com", labelOf(uri))
    }

    @Test
    fun `totp without an issuer has no leading colon and no issuer parameter`() {
        val uri = params(issuer = null, name = "bob").uri()

        assertEquals("bob", labelOf(uri))
        assertNull(Url(uri).parameters["issuer"])
    }

    @Test
    fun `totp without an account name omits the colon and relies on the issuer parameter`() {
        // issuer-only: no trailing colon (the ABNF requires an account name
        // after ':'); the colon-less label is read as the account, and the
        // issuer parameter carries the issuer. Matches the IETF otpauth-uri
        // draft and pyotp / hectorm-otpauth.
        val uri = params(issuer = "Google", name = null).uri()

        assertEquals("Google", labelOf(uri))
        assertEquals("Google", Url(uri).parameters["issuer"])
    }

    @Test
    fun `totp with neither issuer nor name omits the label entirely`() {
        val uri = params(issuer = null, name = null).uri()

        assertEquals("", labelOf(uri))
        assertFalse(uri.contains("/:"), "Degenerate ':' label in uri: $uri")
    }

    @Test
    fun `hotp always includes a counter`() {
        // The migration payload omits a zero counter (proto3 default), but the
        // Key Uri Format requires 'counter' for HOTP.
        val uri = params(type = Type.OTP_TYPE_HOTP, counter = null).uri()

        assertEquals("hotp", Url(uri).host)
        assertEquals("0", Url(uri).parameters["counter"])
    }

    @Test
    fun `hotp preserves an explicit counter`() {
        val uri = params(type = Type.OTP_TYPE_HOTP, counter = 42).uri()

        assertEquals("42", Url(uri).parameters["counter"])
    }

    @Test
    fun `totp carries algorithm digits and period`() {
        val url = Url(
            params(
                algorithm = Algorithm.ALGORITHM_SHA256,
                digits = DigitCount.DIGIT_COUNT_EIGHT,
            ).uri(),
        )

        assertEquals("sha256", url.parameters["algorithm"])
        assertEquals("8", url.parameters["digits"])
        assertEquals("30", url.parameters["period"])
    }

    @Test
    fun `generated totp uri round-trips through TotpToken parse`() {
        val uri = params(secret = ByteArray(11) { it.toByte() }).uri()

        val token = assertIs<Either.Right<TotpToken>>(TotpToken.parse(uri)).value
        val totp = assertIs<TotpToken.TotpAuth>(token)
        assertEquals("AAAQEAYEAUDAOCAJBI", totp.keyBase32)
        assertEquals(6, totp.digits)
        assertEquals(30L, totp.period)
    }
}

private val base32 = Base32ServiceJvm()

private fun params(
    secret: ByteArray = ByteArray(10) { 1 },
    name: String? = "alice@example.com",
    issuer: String? = "Example Co",
    algorithm: Algorithm = Algorithm.ALGORITHM_SHA1,
    digits: DigitCount = DigitCount.DIGIT_COUNT_SIX,
    type: Type = Type.OTP_TYPE_TOTP,
    counter: Int? = null,
) = OtpParameters(
    secret = secret,
    name = name,
    issuer = issuer,
    algorithm = algorithm,
    digits = digits,
    type = type,
    counter = counter,
)

private fun OtpParameters.uri(): String =
    assertIs<Either.Right<String>>(build(base32)).value

/** Extracts the (still percent-encoded) label segment between the host and the query. */
private fun labelOf(uri: String): String =
    uri.substringAfter("://").substringAfter('/').substringBefore('?')
