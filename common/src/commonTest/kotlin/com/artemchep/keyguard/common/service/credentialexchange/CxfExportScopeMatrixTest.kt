package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapCertificateFingerprint
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `mapScope` and the certificate fingerprint beside it.
 *
 * Two things here are only visible as a matrix. The drop-order precedence: a uri
 * can match several of the drop predicates, and which one wins decides whether
 * an android app entry survives at all. And the fingerprint length gate: both
 * directions accept exactly 32 decoded bytes, so anything else is dropped rather
 * than labelled `sha256`. The uncounted losses among these rows are paired with
 * their import twins in `CxfImportSilentDropTest`.
 */
class CxfExportScopeMatrixTest {
    private data class ScopeCase(
        val name: String,
        val uri: DSecret.Uri,
        val url: String? = null,
        val bundleId: String? = null,
    )

    private val scopeCases = listOf(
        ScopeCase("an empty uri is dropped", DSecret.Uri(uri = "")),
        ScopeCase("a whitespace uri is dropped", DSecret.Uri(uri = "   ")),
        ScopeCase(
            name = "a regex-matched url is dropped",
            uri = DSecret.Uri(
                uri = "https://example.com",
                match = DSecret.Uri.MatchType.RegularExpression,
            ),
        ),
        ScopeCase(
            // Precedence: the regex rule is checked before the android branch,
            // so a regex-matched app uri loses its entry entirely.
            name = "a regex-matched android app is dropped, not kept as an app",
            uri = DSecret.Uri(
                uri = "androidapp://com.example.app",
                match = DSecret.Uri.MatchType.RegularExpression,
            ),
        ),
        ScopeCase("a cmd uri is dropped", DSecret.Uri(uri = "cmd://echo hi")),
        ScopeCase(
            name = "the cmd prefix is matched case-insensitively",
            uri = DSecret.Uri(uri = "CMD://echo hi"),
        ),
        ScopeCase(
            // A prefix, not a word: "cmd" alone is an ordinary url.
            name = "a bare cmd is an ordinary url",
            uri = DSecret.Uri(uri = "cmd"),
            url = "cmd",
        ),
        ScopeCase(
            name = "an android app with no bundle id is dropped",
            uri = DSecret.Uri(uri = "androidapp://"),
        ),
        ScopeCase(
            name = "an android app with a blank bundle id is dropped",
            uri = DSecret.Uri(uri = "androidapp://   "),
        ),
        ScopeCase(
            name = "the android prefix is matched case-insensitively",
            uri = DSecret.Uri(uri = "ANDROIDAPP://com.example.app"),
            bundleId = "com.example.app",
        ),
        ScopeCase(
            name = "surrounding whitespace is trimmed",
            uri = DSecret.Uri(uri = "  https://example.com  "),
            url = "https://example.com",
        ),
        ScopeCase(
            name = "a bare domain is kept verbatim",
            uri = DSecret.Uri(uri = "example.com"),
            url = "example.com",
        ),
        ScopeCase(
            name = "an ios app uri is kept verbatim",
            uri = DSecret.Uri(uri = "iosapp://com.example.app"),
            url = "iosapp://com.example.app",
        ),
        ScopeCase(
            name = "a never-match uri is still exported",
            uri = DSecret.Uri(uri = "https://example.com", match = DSecret.Uri.MatchType.Never),
            url = "https://example.com",
        ),
    )

    @Test
    fun `the drop rules apply in a fixed precedence`() {
        scopeCases.forEach { case ->
            val scope = mapScope(listOf(case.uri))
            assertEquals(case.url, scope?.urls?.singleOrNull(), "url of ${case.name}")
            assertEquals(
                case.bundleId,
                scope?.androidApps?.singleOrNull()?.bundleId,
                "bundleId of ${case.name}",
            )
        }
    }

    @Test
    fun `a scope with nothing left is absent`() {
        assertNull(mapScope(emptyList()))
        assertNull(mapScope(listOf(DSecret.Uri(uri = "cmd://x"), DSecret.Uri(uri = "  "))))
        // A regex pattern is dropped like the rest, so a vault entry matched
        // only by one exports with no scope at all.
        assertNull(
            mapScope(
                listOf(
                    DSecret.Uri(uri = "   "),
                    DSecret.Uri(uri = "cmd://echo secret"),
                    DSecret.Uri(
                        uri = "^https://.*\\.example\\.com$",
                        match = DSecret.Uri.MatchType.RegularExpression,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `only the first signature of an android app survives`() {
        val scope = mapScope(
            listOf(
                DSecret.Uri(
                    uri = "androidapp://com.example.app",
                    signatures = listOf(
                        DSecret.Uri.Signature(certFingerprintSha256 = cxfCertFingerprint(seed = 1)),
                        DSecret.Uri.Signature(certFingerprintSha256 = cxfCertFingerprint(seed = 2)),
                    ),
                ),
            ),
        )
        // A key-rotated app carries several fingerprints; the format has one
        // slot, so the rest are lost with no counter.
        val app = scope?.androidApps?.single()
        assertEquals(
            mapCertificateFingerprint(
                DSecret.Uri.Signature(certFingerprintSha256 = cxfCertFingerprint(seed = 1)),
            )?.fingerprint,
            app?.certificate?.fingerprint,
        )
    }

    @Test
    fun `a malformed first signature leaves the app entry intact`() {
        val scope = mapScope(
            listOf(
                DSecret.Uri(
                    uri = "androidapp://com.example.app",
                    signatures = listOf(
                        DSecret.Uri.Signature(certFingerprintSha256 = "ZZ:ZZ"),
                        DSecret.Uri.Signature(certFingerprintSha256 = cxfCertFingerprint()),
                    ),
                ),
            ),
        )
        val app = scope?.androidApps?.single()
        assertEquals("com.example.app", app?.bundleId)
        assertNull(app?.certificate, "a bad first signature must not be replaced by the second")
    }

    private data class FingerprintCase(
        val name: String,
        val hex: String,
        val decodedBytes: Int?,
    )

    private val fingerprintCases = listOf(
        FingerprintCase("colon-separated upper-case hex", cxfCertFingerprint(), 32),
        FingerprintCase("lower-case hex is accepted", cxfCertFingerprint().lowercase(), 32),
        FingerprintCase("colon-less hex is accepted", cxfCertFingerprint().replace(":", ""), 32),
        FingerprintCase("an empty fingerprint is dropped", "", null),
        FingerprintCase("colons alone are dropped", ":::", null),
        FingerprintCase("an odd number of digits is dropped", "ABC", null),
        FingerprintCase("non-hex digits are dropped", "ZZ:ZZ", null),
        FingerprintCase("embedded spaces are dropped", "AB CD", null),
        // The two rows that matter: neither length is a SHA-256 hash, so neither
        // may be emitted under the hard-coded `sha256` label.
        FingerprintCase("a single byte is dropped", "AB", null),
        FingerprintCase("a sha512-length hash is dropped", "AB".repeat(64), null),
    )

    @Test
    fun `the fingerprint is dropped unless it is exactly thirty two bytes`() {
        fingerprintCases.forEach { case ->
            val fingerprint = mapCertificateFingerprint(
                DSecret.Uri.Signature(certFingerprintSha256 = case.hex),
            )
            if (case.decodedBytes == null) {
                assertNull(fingerprint, case.name)
            } else {
                assertEquals("sha256", fingerprint?.hashAlg, case.name)
                assertEquals(
                    case.decodedBytes,
                    fingerprint?.fingerprint?.let(::decodedB64UrlSize),
                    case.name,
                )
            }
        }
    }

    @Test
    fun `a one-sided scope still serializes both arrays`() {
        // Neither member has a default, so an empty list survives encoding as
        // `[]` rather than being omitted.
        val urlsOnly = mapScope(listOf(DSecret.Uri(uri = "https://example.com")))
        assertTrue(urlsOnly?.androidApps?.isEmpty() == true)
        val appsOnly = mapScope(listOf(DSecret.Uri(uri = "androidapp://com.example.app")))
        assertTrue(appsOnly?.urls?.isEmpty() == true)
    }
}
