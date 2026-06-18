package com.artemchep.keyguard.common.service.totp.impl

import arrow.core.Either
import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.service.text.Base32Service
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TotpServiceMobileAuthTest {
    @Test
    fun `generates spec-correct code with a pin`() {
        // md5("170000000" + secret + "1234")[:6]
        assertEquals("1fc701", createService().codeAt(mobileToken(), 1_700_000_000L))
    }

    @Test
    fun `generates spec-correct code without a pin`() {
        // md5("170000000" + secret)[:6] — no pin appended.
        assertEquals("4ed99e", createService().codeAt(mobileToken(pin = null), 1_700_000_000L))
    }

    @Test
    fun `pin is part of the hashed input`() {
        val service = createService()

        val withPin = service.codeAt(mobileToken(pin = "1234"), 1_700_000_000L)
        val withoutPin = service.codeAt(mobileToken(pin = null), 1_700_000_000L)

        assertNotEquals(withoutPin, withPin)
    }

    @Test
    fun `code is stable within the same 10 second window`() {
        val service = createService()

        // 1_700_000_000 and 1_700_000_009 both floor to epoch 170000000.
        val atStart = service.codeAt(mobileToken(), 1_700_000_000L)
        val atEnd = service.codeAt(mobileToken(), 1_700_000_009L)

        assertEquals("1fc701", atStart)
        assertEquals(atStart, atEnd)
    }

    @Test
    fun `code rolls over into the next 10 second window`() {
        val service = createService()

        // 1_700_000_010 floors to epoch 170000001 — a different code.
        val firstWindow = service.codeAt(mobileToken(), 1_700_000_000L)
        val nextWindow = service.codeAt(mobileToken(), 1_700_000_010L)

        assertEquals("701a4c", nextWindow)
        assertNotEquals(firstWindow, nextWindow)
    }

    @Test
    fun `produces distinct codes for distinct 10 second windows inside the same 30 second window`() {
        val service = createService()

        // Regression guard for the old `floor(secs / 30) * 3` quantization bug:
        // 1_699_999_990 and 1_700_000_000 share the 30s window [1_699_999_980, 1_700_000_010),
        // so the buggy code produced epoch 169999998 for BOTH (identical codes). The spec wants
        // epoch 169999999 vs 170000000 — distinct codes.
        val earlier = service.codeAt(mobileToken(), 1_699_999_990L)
        val later = service.codeAt(mobileToken(), 1_700_000_000L)

        assertEquals("7933fa", earlier)
        assertEquals("1fc701", later)
        assertNotEquals(earlier, later)
    }

    @Test
    fun `offset advances the counter by one 10 second step`() {
        val service = createService()

        // offset = +1 step must equal the code 10 seconds later with no offset.
        val offsetByOne = service.codeAt(mobileToken(), 1_700_000_000L, offset = 1)
        val tenSecondsLater = service.codeAt(mobileToken(), 1_700_000_010L)

        assertEquals("701a4c", offsetByOne)
        assertEquals(tenSecondsLater, offsetByOne)
    }

    @Test
    fun `code is six lowercase hex characters`() {
        val code = createService().codeAt(mobileToken(), 1_700_000_000L)

        assertTrue(code.matches(Regex("^[0-9a-f]{6}$")), "Unexpected mOTP code format: $code")
    }

    @Test
    fun `counter expires at the next 10 second boundary`() {
        val timestamp = Instant.fromEpochSeconds(1_700_000_000L)

        val code = createService().generateAt(mobileToken(), 1_700_000_000L)

        val counter = assertIs<TotpCode.TimeBasedCounter>(code.counter)
        assertEquals(timestamp, counter.timestamp)
        assertEquals(Instant.fromEpochSeconds(1_700_000_010L), counter.expiration)
        assertEquals(10.seconds, counter.duration)
    }
}

private const val SECRET = "16e26a7d8f9c0b1a"

private const val PIN = "1234"

private fun createService() = TotpServiceImpl(
    base32Service = StubBase32Service(),
    cryptoGenerator = CryptoGeneratorJvm(),
)

private fun mobileToken(
    secret: String = SECRET,
    pin: String? = PIN,
) = TotpToken.MobileAuth(
    issuer = "Keyguard",
    username = "alice",
    secret = secret,
    pin = pin,
    raw = "motp://Keyguard:alice?secret=$secret" + pin?.let { "&pin=$it" }.orEmpty(),
)

private fun TotpServiceImpl.generateAt(
    token: TotpToken.MobileAuth,
    epochSeconds: Long,
    offset: Int = 0,
): TotpCode {
    val result = generate(token, Instant.fromEpochSeconds(epochSeconds), offset)
    return assertIs<Either.Right<TotpCode>>(result).value
}

private fun TotpServiceImpl.codeAt(
    token: TotpToken.MobileAuth,
    epochSeconds: Long,
    offset: Int = 0,
): String = generateAt(token, epochSeconds, offset).code

/**
 * mOTP never touches base32 (the secret is hashed verbatim), so this stub is
 * only here to satisfy the [TotpServiceImpl] constructor.
 */
private class StubBase32Service : Base32Service {
    override fun encode(bytes: ByteArray): ByteArray = bytes

    override fun decode(bytes: ByteArray): ByteArray = bytes
}
