package com.artemchep.keyguard.common.service.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GpgKeyIdTest {
    @Test
    fun `v4 and v6 key IDs use opposite ends of the fingerprint`() {
        assertEquals("0123456789ABCDEF", ("A".repeat(24) + "0123456789abcdef").gpgKeyIdFromFingerprintOrNull())
        assertEquals("FEDCBA9876543210", ("fedcba9876543210" + "A".repeat(48)).gpgKeyIdFromFingerprintOrNull())
        assertEquals("0000000000000001", ("0000 0000 0000 0001 " + "A".repeat(48)).gpgKeyIdFromFingerprintOrNull())
    }

    @Test
    fun `unsupported and malformed fingerprints do not become key IDs`() {
        listOf("", "A".repeat(16), "A".repeat(32), "A".repeat(63), "A".repeat(128), "Z".repeat(40))
            .forEach { assertNull(it.gpgKeyIdFromFingerprintOrNull()) }
    }
}
