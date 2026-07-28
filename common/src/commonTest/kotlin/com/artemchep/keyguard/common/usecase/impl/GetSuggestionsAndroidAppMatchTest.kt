package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.LinkInfoAndroid
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GetSuggestionsAndroidAppMatchTest {
    @Test
    fun `matches exact package name`() {
        assertTrue(
            androidUri("com.paypal.android").matchesAndroidApp(
                androidTarget("com.paypal.android"),
            ),
        )
    }

    @Test
    fun `rejects package suffix lookalike`() {
        assertFalse(
            androidUri("com.paypal.android").matchesAndroidApp(
                androidTarget("paypal.android"),
            ),
        )
    }

    @Test
    fun `rejects package prefix lookalike`() {
        assertFalse(
            androidUri("com.paypal.android").matchesAndroidApp(
                androidTarget("com.paypal.android.evil"),
            ),
        )
    }

    @Test
    fun `unsigned uri only requires exact package`() {
        assertTrue(
            androidUri("com.example.app").matchesAndroidApp(
                androidTarget(
                    packageName = "com.example.app",
                    signingCertificates = null,
                ),
            ),
        )
    }

    @Test
    fun `signed uri fails closed when target certificates are unavailable`() {
        assertFalse(
            androidUri(
                packageName = "com.example.app",
                fingerprints = listOf(FINGERPRINT_A),
            ).matchesAndroidApp(
                androidTarget(
                    packageName = "com.example.app",
                    signingCertificates = null,
                ),
            ),
        )
    }

    @Test
    fun `signed uri accepts certificate from verified signing history`() {
        assertTrue(
            androidUri(
                packageName = "com.example.app",
                fingerprints = listOf(FINGERPRINT_A.lowercase().chunked(2).joinToString(":")),
            ).matchesAndroidApp(
                androidTarget(
                    packageName = "com.example.app",
                    signingCertificates = singleSignerCertificates(
                        current = FINGERPRINT_B,
                        history = setOf(FINGERPRINT_A, FINGERPRINT_B),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `signed uri rejects different certificate`() {
        assertFalse(
            androidUri(
                packageName = "com.example.app",
                fingerprints = listOf(FINGERPRINT_A),
            ).matchesAndroidApp(
                androidTarget(
                    packageName = "com.example.app",
                    signingCertificates = singleSignerCertificates(
                        current = FINGERPRINT_B,
                        history = setOf(FINGERPRINT_B),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `multi signer app requires every current signer`() {
        val target = androidTarget(
            packageName = "com.example.app",
            signingCertificates = LinkInfoAndroid.SigningCertificates(
                current = setOf(FINGERPRINT_A, FINGERPRINT_B),
                history = emptySet(),
                hasMultipleSigners = true,
            ),
        )

        assertTrue(
            androidUri(
                packageName = "com.example.app",
                fingerprints = listOf(FINGERPRINT_A, FINGERPRINT_B),
            ).matchesAndroidApp(target),
        )
        assertFalse(
            androidUri(
                packageName = "com.example.app",
                fingerprints = listOf(FINGERPRINT_A),
            ).matchesAndroidApp(target),
        )
    }

    private fun androidUri(
        packageName: String,
        fingerprints: List<String> = emptyList(),
    ) = DSecret.Uri(
        uri = "androidapp://$packageName",
        signatures = fingerprints.map { fingerprint ->
            DSecret.Uri.Signature(
                certFingerprintSha256 = fingerprint,
            )
        },
    )

    private fun androidTarget(
        packageName: String,
        signingCertificates: LinkInfoAndroid.SigningCertificates? = null,
    ) = GetSuggestionsImpl.LocalAutofillTargetAndroid(
        uri = "androidapp://$packageName",
        appId = packageName,
        appName = null,
        signingCertificates = signingCertificates,
    )

    private fun singleSignerCertificates(
        current: String,
        history: Set<String>,
    ) = LinkInfoAndroid.SigningCertificates(
        current = setOf(current),
        history = history,
        hasMultipleSigners = false,
    )

    private companion object {
        const val FINGERPRINT_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val FINGERPRINT_B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
    }
}
