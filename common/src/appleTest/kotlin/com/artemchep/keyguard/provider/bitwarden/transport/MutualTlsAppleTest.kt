@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.artemchep.keyguard.provider.bitwarden.transport

import platform.Foundation.NSURLCredentialPersistence
import platform.Foundation.certificates
import platform.Foundation.identity
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class MutualTlsAppleTest {
    @Test
    fun normalizedBundleImportsAsSessionIdentityWithCertificateChain() {
        val source = Base64.decode(TEST_BUNDLE)
        val imported = try {
            assertIs<MutualTlsImportResult.Success>(MutualTlsServiceNative().importPkcs12(source, null)).imported
        } finally {
            source.fill(0)
        }
        val credential = createMutualTlsCredential(imported.pkcs12Base64)
        assertNotNull(credential.identity())
        assertEquals(2, credential.certificates().size)
        assertEquals(NSURLCredentialPersistence.NSURLCredentialPersistenceNone, credential.persistence)
    }
}

// Synthetic P-256 client and CA chain from the Rust PKCS#12 fixtures; password is empty.
private const val TEST_BUNDLE =
    "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"
