package com.artemchep.keyguard.provider.keyguard.applink

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AppLinkPrivilegedAppsTest : AppSyncRowContractTest(PrivilegedAppTestRows) {
    private companion object {
        const val BROWSER = "com.example.browser"

        val OTHER_FINGERPRINT = TEST_CERT_FINGERPRINT.replace("AB", "0F")
    }

    @Test
    fun `the same package with another certificate is another privileged app`() = runTest {
        val (a, b) = linkedPair {
            a.addPrivilegedApp(BROWSER)
            b.addPrivilegedApp(BROWSER, OTHER_FINGERPRINT)
        }
        a.exchangeWith(b)

        val fingerprints = setOf(TEST_CERT_FINGERPRINT, OTHER_FINGERPRINT)
        assertEquals(fingerprints, a.privilegedAppList().map { it.certFingerprintSha256 }.toSet())
        assertEquals(fingerprints, b.privilegedAppList().map { it.certFingerprintSha256 }.toSet())
    }
}
