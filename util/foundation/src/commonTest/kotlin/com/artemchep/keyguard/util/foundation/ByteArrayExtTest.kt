package com.artemchep.keyguard.util.foundation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ByteArrayExtTest {
    @Test
    fun isAllZeroAcceptsEmptyAndZeroFilledArrays() {
        assertTrue(byteArrayOf().isAllZero())
        assertTrue(ByteArray(32).isAllZero())
    }

    @Test
    fun isAllZeroRejectsNonZeroBytes() {
        assertFalse(byteArrayOf(1, 0, 0).isAllZero())
        assertFalse(byteArrayOf(0, 1, 0).isAllZero())
        assertFalse(byteArrayOf(0, 0, (-1).toByte()).isAllZero())
    }
}
