package com.artemchep.jna.util

import kotlin.test.Test
import kotlin.test.assertEquals

class PointerFromStringTest {
    @Test
    fun `as memory allocates and writes utf8 strings`() {
        val values = listOf(
            "",
            "password",
            "caf\u00e9",
            "\u65e5\u672c\u8a9e",
            "\uD83D\uDD10",
        )

        values.forEach { value ->
            val bytes = value.encodeToByteArray()
            val disposableMemory = value.asMemory()
            try {
                assertEquals(bytes.size + 1L, disposableMemory.memory.size())
                assertEquals(value, disposableMemory.memory.getString(0L, Charsets.UTF_8.name()))
                assertEquals(0, disposableMemory.memory.getByte(bytes.size.toLong()).toInt())
            } finally {
                disposableMemory.dispose()
            }
        }
    }
}
