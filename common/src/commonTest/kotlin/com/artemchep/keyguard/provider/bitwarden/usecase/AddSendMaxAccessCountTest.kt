package com.artemchep.keyguard.provider.bitwarden.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AddSendMaxAccessCountTest {
    @Test
    fun `number is parsed`() {
        assertEquals(1, parseSendMaxAccessCount("1"))
        assertEquals(42, parseSendMaxAccessCount("42"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(1, parseSendMaxAccessCount(" 1"))
        assertEquals(1, parseSendMaxAccessCount("1 "))
        assertEquals(1, parseSendMaxAccessCount(" 1 "))
    }

    @Test
    fun `blank means no limit`() {
        assertNull(parseSendMaxAccessCount(null))
        assertNull(parseSendMaxAccessCount(""))
        assertNull(parseSendMaxAccessCount("   "))
    }

    @Test
    fun `non blank invalid value is rejected instead of becoming no limit`() {
        assertFailsWith<IllegalStateException> {
            parseSendMaxAccessCount("abc")
        }
        assertFailsWith<IllegalStateException> {
            parseSendMaxAccessCount("1a")
        }
        assertFailsWith<IllegalStateException> {
            // Overflows Int.
            parseSendMaxAccessCount("99999999999")
        }
    }
}
