package com.artemchep.keyguard.common.service.totp.impl

import arrow.core.Either
import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.copy.Base32ServiceJvm
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TotpServiceSteamAuthTest {
    @Test
    fun `keepassxc and steam uris generate the same codes across a period boundary`() {
        val service = TotpServiceImpl(
            base32Service = Base32ServiceJvm(),
            cryptoGenerator = CryptoGeneratorJvm(),
        )
        val raw = "otpauth://totp/Steam:alice?secret=JBSWY3DPEHPK3PXP&period=30&digits=5&issuer=Steam&encoder=steam"
        val imported = assertNotNull(TotpToken.parse(raw).getOrNull())
        val existing = assertNotNull(TotpToken.parse("steam://JBSWY3DPEHPK3PXP").getOrNull())

        // Independent HMAC-SHA1 vectors, encoded with the Steam alphabet.
        listOf(
            1_700_000_000L to "2KM2P",
            1_700_000_009L to "2KM2P",
            1_700_000_010L to "3D7YK",
        ).forEach { (epochSeconds, expected) ->
            val timestamp = Instant.fromEpochSeconds(epochSeconds)
            val code = assertIs<Either.Right<TotpCode>>(service.generate(imported, timestamp)).value
            val legacyCode = assertIs<Either.Right<TotpCode>>(service.generate(existing, timestamp)).value

            assertEquals(expected, code.code)
            assertEquals(legacyCode, code)
            val counter = assertIs<TotpCode.TimeBasedCounter>(code.counter)
            assertEquals(30.seconds, counter.duration)
            assertEquals(Instant.fromEpochSeconds((epochSeconds / 30L + 1L) * 30L), counter.expiration)
        }
    }
}
