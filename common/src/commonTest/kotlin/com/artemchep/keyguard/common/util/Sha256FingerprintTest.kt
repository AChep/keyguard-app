package com.artemchep.keyguard.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Sha256FingerprintTest {
    private companion object {
        const val FINGERPRINT_WITHOUT_SEPARATORS =
            "00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF"

        const val CANONICAL_FINGERPRINT =
            "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:" +
                    "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"
    }

    @Test
    fun `normalizes sha256 fingerprint`() {
        assertEquals(
            CANONICAL_FINGERPRINT,
            FINGERPRINT_WITHOUT_SEPARATORS.lowercase().normalizeSha256FingerprintOrNull(),
        )
    }

    @Test
    fun `rejects invalid sha256 fingerprint`() {
        assertNull("AA:BB".normalizeSha256FingerprintOrNull())
        assertNull("GG${FINGERPRINT_WITHOUT_SEPARATORS.drop(2)}".normalizeSha256FingerprintOrNull())
    }
}
