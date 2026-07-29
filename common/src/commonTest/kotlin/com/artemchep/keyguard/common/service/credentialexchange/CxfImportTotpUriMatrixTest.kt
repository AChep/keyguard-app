package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapImportTotpUri
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * `mapImportTotpUri` as an exact-string grid.
 *
 * The importer builds a uri out of untrusted parts and hands it to
 * `TotpToken.parse`, so two things are pinned here. First the omission rules:
 * only values equal to the otpauth defaults (period 30, 6 digits, SHA-1) are
 * omitted, and an algorithm CXF v1.0 §3.3.16 does not name is a refusal rather
 * than an omission. Second injection resistance: the secret is canonicalized to
 * `[A-Z2-7]` before the builder sees it, while the free-form issuer and username
 * are percent-encoded — except the label's `:` separator, which the Key Uri
 * Format also reads as `%3A`, so it is dropped from the label instead.
 */
class CxfImportTotpUriMatrixTest {
    private data class UriCase(
        val name: String,
        val totp: CxfCredential.Totp,
        val expected: String?,
    )

    private val uriCases = listOf(
        UriCase(
            name = "the defaults are omitted",
            totp = totp(),
            expected = "otpauth://totp/?secret=JBSWY3DPEHPK3PXP",
        ),
        UriCase(
            name = "a username becomes the label",
            totp = totp(username = "alice"),
            expected = "otpauth://totp/alice?secret=JBSWY3DPEHPK3PXP",
        ),
        UriCase(
            name = "an issuer joins the label and the query",
            totp = totp(username = "alice", issuer = "Example"),
            expected = "otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example",
        ),
        UriCase(
            // No username to pair with, so the label is empty even though the
            // issuer is still emitted as a parameter.
            name = "an issuer without a username leaves the label empty",
            totp = totp(issuer = "Example"),
            expected = "otpauth://totp/?secret=JBSWY3DPEHPK3PXP&issuer=Example",
        ),
        UriCase(
            name = "a non-default period is emitted",
            totp = totp(period = 60),
            expected = "otpauth://totp/?secret=JBSWY3DPEHPK3PXP&period=60",
        ),
        UriCase(
            name = "a non-default digit count is emitted",
            totp = totp(digits = 8),
            expected = "otpauth://totp/?secret=JBSWY3DPEHPK3PXP&digits=8",
        ),
        UriCase(
            name = "sha256 is emitted",
            totp = totp(algorithm = "sha256"),
            expected = "otpauth://totp/?secret=JBSWY3DPEHPK3PXP&algorithm=SHA256",
        ),
        UriCase(
            name = "sha512 is emitted",
            totp = totp(algorithm = "sha512"),
            expected = "otpauth://totp/?secret=JBSWY3DPEHPK3PXP&algorithm=SHA512",
        ),
        UriCase(
            name = "the algorithm is trimmed and case-folded",
            totp = totp(algorithm = " SHA256 "),
            expected = "otpauth://totp/?secret=JBSWY3DPEHPK3PXP&algorithm=SHA256",
        ),
        UriCase(
            name = "the minimum period is emitted",
            totp = totp(period = 1),
            expected = "otpauth://totp/?secret=JBSWY3DPEHPK3PXP&period=1",
        ),
        UriCase(
            // Both separators are dropped and the alphabet survives, so the
            // decoded bytes are identical to what the source document meant.
            name = "a hyphen-grouped secret is canonicalized into the uri",
            totp = totp(secret = "jbsw-y3dp ehpk-3pxp"),
            expected = "otpauth://totp/?secret=JBSWY3DPEHPK3PXP",
        ),
    )

    @Test
    fun `the uri is built with the defaults omitted`() {
        uriCases.forEach { case ->
            assertEquals(case.expected, mapImportTotpUri(case.totp), case.name)
        }
    }

    @Test
    fun `the steam form is produced for every casing`() {
        // The marker is trimmed and case-folded like every other algorithm
        // value; a case-sensitive comparison would turn "Steam" into an otpauth
        // SHA-1 credential carrying a Steam secret, which can never produce a
        // valid code.
        listOf("steam", "Steam", "STEAM", " steam ").forEach { algorithm ->
            assertEquals(
                "steam://JBSWY3DPEHPK3PXP",
                mapImportTotpUri(totp(algorithm = algorithm)),
                algorithm,
            )
        }
    }

    private val nullCases = listOf(
        "an empty secret" to totp(secret = ""),
        "a whitespace secret" to totp(secret = "   "),
        "a tab secret" to totp(secret = "\t"),
        "zero digits" to totp(digits = 0),
        "ten digits" to totp(digits = 10),
        "a non-positive period is refused" to totp(period = 0),
        // Canonicalization removes the secret-injection surface entirely
        // rather than encoding around it: `&` is not in the base32 alphabet,
        // so the whole credential is refused before a uri is ever built.
        "a secret carrying a query separator" to totp(secret = "JBSWY3DP&period=1"),
    )

    @Test
    fun `a uri the parser would reject is not produced`() {
        nullCases.forEach { (name, totp) ->
            assertNull(mapImportTotpUri(totp), name)
        }
    }

    @Test
    fun `md5 never reaches the uri`() {
        // Emitting the parameter and leaning on `TotpToken.parse` to reject it
        // would still let `sha3-256` through as a silent SHA-1, so the refusal
        // has to happen before the uri is built.
        listOf("md5", "MD5", " Md5 ", "sha3-256", "sm3", "", "  ").forEach { algorithm ->
            assertNull(mapImportTotpUri(totp(algorithm = algorithm)), algorithm)
        }
        uriCases.forEach { case ->
            val uri = requireNotNull(mapImportTotpUri(case.totp), { case.name })
            assertFalse(uri.contains("algorithm=md5", ignoreCase = true), case.name)
        }
    }

    // region Injection resistance

    private data class InjectionCase(
        val name: String,
        val totp: CxfCredential.Totp,
        val expectedSecret: String,
        val expectedIssuer: String? = null,
        val expectedUsername: String? = null,
    )

    private val injectionCases = listOf(
        InjectionCase(
            name = "an issuer carrying a query separator",
            totp = totp(username = "alice", issuer = "a?b=c"),
            expectedSecret = "JBSWY3DPEHPK3PXP",
            expectedIssuer = "a?b=c",
            expectedUsername = "alice",
        ),
        InjectionCase(
            name = "a username carrying path traversal",
            totp = totp(username = "../../etc/passwd"),
            expectedSecret = "JBSWY3DPEHPK3PXP",
            expectedUsername = "../../etc/passwd",
        ),
        InjectionCase(
            // The one character the label reserves. Percent-encoding it buys
            // nothing — the Key Uri Format reads `%3A` as the separator too, so
            // it would parse back as issuer "alice" plus account "bob", an issuer
            // the source document never had. Dropped from the label instead.
            name = "a username carrying the label separator",
            totp = totp(username = "alice:bob"),
            expectedSecret = "JBSWY3DPEHPK3PXP",
            expectedIssuer = null,
            expectedUsername = "alicebob",
        ),
        InjectionCase(
            // The issuer is the half that can still round-trip exactly: the
            // `issuer=` query parameter carries it verbatim and the parser
            // prefers it over the label prefix.
            name = "an issuer carrying the label separator",
            totp = totp(username = "alice", issuer = "ACME:Inc"),
            expectedSecret = "JBSWY3DPEHPK3PXP",
            expectedIssuer = "ACME:Inc",
            expectedUsername = "alice",
        ),
        InjectionCase(
            name = "a username that is nothing but the label separator",
            totp = totp(username = ":", issuer = "Example"),
            expectedSecret = "JBSWY3DPEHPK3PXP",
            expectedIssuer = "Example",
            expectedUsername = null,
        ),
    )

    @Test
    fun `hostile parts are encoded rather than injected`() {
        injectionCases.forEach { case ->
            val uri = mapImportTotpUri(case.totp)
            val token = assertIs<TotpToken.TotpAuth>(
                TotpToken.parse(requireNotNull(uri)).getOrNull(),
                case.name,
            )
            assertEquals(case.expectedSecret, token.keyBase32, case.name)
            assertEquals(case.expectedIssuer, token.issuer, case.name)
            assertEquals(case.expectedUsername, token.username, case.name)
            // Nothing hostile changed the configuration.
            assertEquals(30L, token.period, case.name)
            assertEquals(6, token.digits, case.name)
        }
    }

    // endregion

    @Test
    fun `the vault reads back what the importer wrote`() {
        // The string assertions above only matter if the parser agrees, so the
        // one row where every emitted parameter is non-default is round-tripped
        // through `TotpToken.parse`. The default-valued rows are covered by the
        // injection cases, which parse back a 30/6 configuration.
        val uri = requireNotNull(
            mapImportTotpUri(
                totp(username = "alice", issuer = "Example", digits = 8, period = 60, algorithm = "sha256"),
            ),
        )
        val token = assertIs<TotpToken.TotpAuth>(TotpToken.parse(uri).getOrNull())
        assertEquals(8, token.digits)
        assertEquals(60L, token.period)
        assertEquals(CryptoHashAlgorithm.SHA_256, token.algorithm)
    }
}

@Suppress("LongParameterList")
private fun totp(
    secret: String = "JBSWY3DPEHPK3PXP",
    period: Int = 30,
    digits: Int = 6,
    algorithm: String = "sha1",
    username: String? = null,
    issuer: String? = null,
): CxfCredential.Totp = CxfCredential.Totp(
    secret = secret,
    period = period,
    digits = digits,
    algorithm = algorithm,
    username = username,
    issuer = issuer,
)
