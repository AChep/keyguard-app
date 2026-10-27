package com.artemchep.keyguard.common.service.gpgagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GpgIdentifiersTest {
    private val fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"

    @Test
    fun `fingerprint URI is upper-case with no separators`() {
        listOf(
            "abcd ef01 2345 6789 abcd ef01 2345 6789 abcd ef01",
            "0x$fingerprint",
        ).forEach { value ->
            assertEquals("OPENPGP4FPR:$fingerprint", value.toGpgFingerprintUriOrNull(), value)
        }
    }

    @Test
    fun `only a fingerprint has a fingerprint URI`() {
        listOf("", fingerprint.takeLast(16), "A".repeat(32), "0x0x$fingerprint", "${fingerprint}Z")
            .forEach { assertNull(it.toGpgFingerprintUriOrNull(), it) }
    }

    @Test
    fun `fingerprint URI round-trips`() {
        assertEquals(fingerprint, fingerprint.toGpgFingerprintUriOrNull()?.parseGpgFingerprintUriOrNull())
    }

    @Test
    fun `parse accepts any scheme case and drops parameters`() {
        assertEquals(
            fingerprint,
            " openpgp4fpr:${fingerprint.lowercase()}#a=alice%40example.com&n=Alice ".parseGpgFingerprintUriOrNull(),
        )
    }

    @Test
    fun `parse rejects anything that is not a fingerprint URI`() {
        assertNull(fingerprint.parseGpgFingerprintUriOrNull())
        assertNull("OPENPGP4FPR:".parseGpgFingerprintUriOrNull())
        assertNull("OPENPGP4FPR:#a=alice%40example.com".parseGpgFingerprintUriOrNull())
        assertNull("OPENPGP4FPR:alice".parseGpgFingerprintUriOrNull())
        assertNull("OPENPGP4FPR:ABC".parseGpgFingerprintUriOrNull())
        assertNull("OPENPGP4FPR:${fingerprint.takeLast(16)}".parseGpgFingerprintUriOrNull())
        assertNull("https://example.com/OPENPGP4FPR:$fingerprint".parseGpgFingerprintUriOrNull())
    }
}
