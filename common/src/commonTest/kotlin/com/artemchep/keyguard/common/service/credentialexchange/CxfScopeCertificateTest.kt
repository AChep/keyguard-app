package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapCertificateFingerprint
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapImportCertificateFingerprint
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapScope
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfAndroidAppCertificateFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The `scope` member and the android-app certificate fingerprint inside it. The
 * fingerprint is the one place where the exporter and the importer have to agree
 * on a *validator*, so both directions are covered here rather than beside their
 * own credential mappers.
 */
class CxfScopeCertificateTest {

    @Test
    fun `scope splits urls and android apps`() {
        val uris = listOf(
            DSecret.Uri(uri = "https://example.com"),
            DSecret.Uri(uri = "androidapp://com.example.app"),
            DSecret.Uri(uri = "ftp://files.example.com"),
        )
        val scope = mapScope(uris)
        assertEquals(listOf("https://example.com", "ftp://files.example.com"), scope?.urls)
        assertEquals(1, scope?.androidApps?.size)
        assertEquals("com.example.app", scope?.androidApps?.first()?.bundleId)
    }

    @Test
    fun `certificate fingerprint converts colon hex to base64url`() {
        // The literal is the base64url of `cxfCertFingerprint()`'s 32 bytes
        // (0x00..0x1F), written out rather than recomputed so the test states
        // the wire value instead of restating the implementation.
        val result = mapCertificateFingerprint(
            DSecret.Uri.Signature(certFingerprintSha256 = cxfCertFingerprint()),
        )
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", result?.fingerprint)
        assertEquals(CxfAndroidAppCertificateFingerprint.HASH_ALG_SHA256, result?.hashAlg)
    }

    @Test
    fun `certificate fingerprint requires exactly thirty two bytes`() {
        // Both directions share one validator: the hard-coded `sha256` label
        // must never sit on something that is not a SHA-256 hash.
        assertNull(mapCertificateFingerprint(DSecret.Uri.Signature("AB")))
        assertNull(mapCertificateFingerprint(DSecret.Uri.Signature("AB".repeat(31))))
        assertNull(mapCertificateFingerprint(DSecret.Uri.Signature("AB".repeat(33))))
        assertNull(mapCertificateFingerprint(DSecret.Uri.Signature("AB".repeat(64))))
        assertNotNull(mapCertificateFingerprint(DSecret.Uri.Signature(cxfCertFingerprint())))
    }

    @Test
    fun `certificate fingerprint export and import are inverses`() {
        // Every spelling the exporter accepts comes back as the canonical
        // upper-case colon-separated form, so the two validators cannot diverge.
        val canonical = cxfCertFingerprint()
        listOf(canonical, canonical.lowercase(), canonical.replace(":", ""))
            .forEach { spelling ->
                val wire = mapCertificateFingerprint(DSecret.Uri.Signature(spelling))
                assertNotNull(wire, spelling)
                assertEquals(
                    canonical,
                    mapImportCertificateFingerprint(wire)?.certFingerprintSha256,
                    spelling,
                )
            }
    }
}
