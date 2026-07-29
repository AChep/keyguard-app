package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.service.credentialexchange.impl.mapTotp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * §3.3.16 requires a TOTP `secret` to be base32, and nothing validates the
 * stored secret on the way into the vault — `TotpToken` copies an otpauth
 * `secret=` parameter verbatim and treats a bare string as the secret itself.
 * Keyguard's own generator tolerates far more than base32, so what round-trips
 * locally is not necessarily readable by the receiving application.
 */
class CxfTotpSecretTest {
    @Test
    fun `padding is stripped`() {
        val totp = mapTotp(cxfTotpAuth(keyBase32 = "JBSWY3DP======").token)
        assertEquals("JBSWY3DP", assertNotNull(totp).secret)
    }

    @Test
    fun `a secret that is not base32 becomes a skip`() {
        // Emitting these would hand the importer a secret it cannot decode,
        // which is worse than telling the user the credential was skipped.
        // Padding alone is not a secret either: the alphabet pattern requires
        // at least one `[A-Za-z2-7]` before the `=`s.
        listOf(
            "not a secret!",
            "0189",
            "JBSWY3DP@EHPK",
            "",
            "======",
        ).forEach { secret ->
            assertNull(
                mapTotp(cxfTotpAuth(keyBase32 = secret).token),
                "secret: $secret",
            )
        }
    }

    @Test
    fun `a secret of an impossible base32 length is still exported`() {
        // Eight characters encode five bytes, so a remainder of one, three or six
        // cannot come from any byte sequence. Remainders of three and six still
        // decode to the same bytes on every platform, so they are working
        // credentials; the alphabet check is what protects the receiver.
        //
        // A remainder of one is NOT cross-platform consistent: the JVM decoder
        // synthesises an extra byte out of the trailing five bits while the iOS
        // one emits whole bytes only, so the same secret yields different keys.
        //
        // Accepted deliberately: the divergence is app-wide — Bitwarden sync,
        // KeePass sync and manual entry all reach it — CXF is a verbatim
        // passthrough, and refusing would destroy a credential that works for the
        // far more common same-platform migration.
        listOf("ABC", "ABCDEF", "A", "ABCDEFGHI").forEach { secret ->
            assertEquals(
                secret.uppercase(),
                assertNotNull(mapTotp(cxfTotpAuth(keyBase32 = secret).token)).secret,
                "secret: $secret",
            )
        }
    }

    @Test
    fun `a mixed separator secret is normalized`() {
        // Lower case, spaces and hyphens at once — every spelling Keyguard
        // generates codes from today, since its base32 decoder skips whatever is
        // not in the alphabet. Refusing them on the way out would be a false
        // negative.
        val totp = mapTotp(cxfTotpAuth(keyBase32 = "jbsw y3dp-ehpk 3pxp").token)
        assertEquals("JBSWY3DPEHPK3PXP", assertNotNull(totp).secret)
    }
}
