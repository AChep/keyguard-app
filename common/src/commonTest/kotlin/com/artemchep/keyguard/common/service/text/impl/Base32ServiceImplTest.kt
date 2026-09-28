package com.artemchep.keyguard.common.service.text.impl

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Base32ServiceImplTest {
    private val service = Base32ServiceImpl()

    @Test
    fun `RFC 4648 vectors encode and decode`() {
        val vectors = mapOf(
            "" to "",
            "f" to "MY======",
            "fo" to "MZXQ====",
            "foo" to "MZXW6===",
            "foob" to "MZXW6YQ=",
            "fooba" to "MZXW6YTB",
            "foobar" to "MZXW6YTBOI======",
        )
        vectors.forEach { (plain, encoded) ->
            assertEquals(encoded, service.encodeToString(plain))
            assertEquals(plain, service.decodeToString(encoded))
            assertEquals(plain, service.decodeToString(encoded.trimEnd('=')))
        }
    }

    @Test
    fun `all byte values round trip at every block boundary`() {
        val bytes = ByteArray(256) { it.toByte() }
        for (length in 0..bytes.size) {
            val input = bytes.copyOf(length)
            assertContentEquals(input, service.decode(service.encode(input)), "length=$length")
        }
    }

    @Test
    fun `human entered secrets retain Apple decoding rules`() {
        assertEquals("foobar", service.decodeToString(" mzxw-6ytb\noi======\t"))
        assertFailsWith<IllegalArgumentException> { service.decode("MZXW6!") }
        assertFailsWith<IllegalArgumentException> { service.decode("MZXW60") }
    }
}
