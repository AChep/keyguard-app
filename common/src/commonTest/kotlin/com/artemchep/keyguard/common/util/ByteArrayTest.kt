package com.artemchep.keyguard.common.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ByteArrayTest {
    @Test
    fun `toHex encodes bytes as lowercase hex`() {
        val bytes = byteArrayOf(0x00, 0x0f, 0x10, 0xff.toByte())

        assertEquals("000f10ff", bytes.toHex())
    }

    @Test
    fun `hexToByteArray decodes uppercase and lowercase hex`() {
        val bytes = "000F10ff".hexToByteArray()

        assertContentEquals(
            byteArrayOf(0x00, 0x0f, 0x10, 0xff.toByte()),
            bytes,
        )
    }

    @Test
    fun `hexToByteArray rejects odd length hex`() {
        assertFailsWith<IllegalArgumentException> {
            "f".hexToByteArray()
        }
    }
}
