package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfTotpAlgorithm
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapImportTotpUri
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapTotp
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapTotpAlgorithm
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The CXF TOTP contract as two properties rather than a pile of examples.
 *
 * (a) CLOSURE — everything `mapTotp` emits, `mapImportTotpUri` accepts and the
 *     vault re-exports identically, which is what makes a Keyguard-to-Keyguard
 *     round trip lossless.
 * (b) SHARED PREDICATE — admissibility of `algorithm`, `secret`, `period` and
 *     `digits` is one function consulted by both directions, so the two mappers
 *     cannot disagree about a member.
 *
 * The stronger "import accepts iff export accepts" does not hold and is not
 * asserted: the steam branch imports `{steam, period: 0, digits: 99}` while the
 * export can only ever emit `{steam, 30, 5}`. Pinned in the last test.
 */
class CxfTotpContractTest {
    private val exportableTokens = listOf(
        cxfTotpAuth(),
        cxfTotpAuth(algorithm = CryptoHashAlgorithm.SHA_256, digits = 8, period = 60L),
        cxfTotpAuth(algorithm = CryptoHashAlgorithm.SHA_512, digits = 1, period = 1L),
        cxfTotpAuth(username = "alice", issuer = "ACME"),
        // The character the otpauth label reserves. Closure holds because the
        // `issuer=` query parameter carries it verbatim and the parser prefers
        // that over the label prefix.
        cxfTotpAuth(username = "alice", issuer = "ACME:Inc"),
        cxfSteamTotp(),
    )

    @Test
    fun `every exportable token survives a wire round trip`() {
        exportableTokens.forEach { stored ->
            val name = stored.raw
            // The fallback username is supplied on both hops because Steam
            // erases the wire `username` on the way back — see
            // `CxfRoundTripNormalizer`.
            val wire = assertNotNull(mapTotp(stored.token, fallbackUsername = "alice"), name)
            val uri = assertNotNull(mapImportTotpUri(wire), name)
            val reparsed = assertNotNull(TotpToken.parse(uri).getOrNull(), name)
            val reexported = assertNotNull(mapTotp(reparsed, fallbackUsername = "alice"), name)
            assertEquals(wire, reexported, name)
        }
    }

    @Test
    fun `the algorithm vocabulary is closed and shared`() {
        val accepted = mapOf(
            "sha1" to CxfTotpAlgorithm.Hash.Sha1,
            "sha256" to CxfTotpAlgorithm.Hash.Sha256,
            "sha512" to CxfTotpAlgorithm.Hash.Sha512,
            "steam" to CxfTotpAlgorithm.Steam,
        )
        accepted.forEach { (wire, expected) ->
            listOf(wire, wire.uppercase(), " $wire ").forEach { spelling ->
                assertEquals(expected, CxfTotpAlgorithm.fromWireOrNull(spelling), spelling)
            }
        }
        listOf("md5", "MD5", "sha3-256", "sm3", "sha-1", "", "  ", "steamy").forEach { wire ->
            assertNull(CxfTotpAlgorithm.fromWireOrNull(wire), wire)
        }
        // The export side names exactly the three hash members asserted above,
        // so a new member on either side fails here.
        assertEquals(
            listOf("sha1", "sha256", "sha512"),
            CryptoHashAlgorithm.entries.mapNotNull(::mapTotpAlgorithm).map { it.wire },
        )
    }

    @Test
    fun `the steam branch discards every other parameter without range-checking it`() {
        // The hole in the "accepts iff accepts" symmetry: `steam://` carries only
        // the secret, and `TotpToken.SteamAuth` fixes every other member
        // regardless of what the source document claimed. The label parts are
        // dropped, and `period`/`digits` values that would refuse the credential
        // outright in any other branch are never even looked at — the steam
        // branch returns before the range gate.
        assertEquals(
            "steam://JBSWY3DPEHPK3PXP",
            mapImportTotpUri(
                CxfCredential.Totp(
                    secret = "JBSWY3DPEHPK3PXP",
                    period = 0,
                    digits = 99,
                    algorithm = "steam",
                    username = "alice",
                    issuer = "Example",
                ),
            ),
        )
    }
}
