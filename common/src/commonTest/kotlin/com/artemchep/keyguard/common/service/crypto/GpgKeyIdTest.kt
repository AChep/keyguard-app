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

    @Test
    fun `fingerprints and key IDs accept an optional 0x prefix`() {
        val fingerprint = "A".repeat(24) + "0123456789ABCDEF"
        listOf(fingerprint, "0x$fingerprint", " 0X${fingerprint.lowercase()} ")
            .forEach { assertEquals(fingerprint, it.parseGpgFingerprintOrNull()) }
        listOf("0123456789ABCDEF", "0x0123456789abcdef", " 0X0123456789ABCDEF ")
            .forEach { assertEquals("0123456789ABCDEF", it.parseGpgKeyIdOrNull()) }
        assertEquals("DEADBEEF", "0xdeadbeef".parseGpgKeyIdOrNull())
    }

    @Test
    fun `whitespace, colons and dashes may separate the digits`() {
        val fingerprint = "A".repeat(24) + "0123456789ABCDEF"
        listOf(" ", ":", "-")
            .map { fingerprint.chunked(2).joinToString(it) }
            .forEach { assertEquals(fingerprint, it.parseGpgFingerprintOrNull(), it) }
        listOf("dead beef", "DE:AD:BE:EF", "dead-beef")
            .forEach { assertEquals("DEADBEEF", it.parseGpgKeyIdOrNull(), it) }
        listOf("ed@cafe.de", "dead.beef", "dead_beef", "dead/beef")
            .forEach { assertNull(it.parseGpgKeyIdOrNull(), it) }
    }

    @Test
    fun `other lengths and non-hex strings are neither fingerprints nor key IDs`() {
        listOf("", "0x", "A".repeat(16), "A".repeat(32), "A".repeat(63), "Z".repeat(40), "0x0x" + "A".repeat(40))
            .forEach { assertNull(it.parseGpgFingerprintOrNull(), it) }
        listOf("", "0x", "A".repeat(7), "A".repeat(12), "A".repeat(40), "Z".repeat(16), "alice@example.com")
            .forEach { assertNull(it.parseGpgKeyIdOrNull(), it) }
    }
}
