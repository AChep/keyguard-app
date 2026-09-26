package com.artemchep.keyguard.common.util

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StringFormatTest {
    @Test
    fun `keeps plain text unchanged`() = runTest {
        assertEquals("hello", "hello".simpleFormat2 { null })
    }

    @Test
    fun `replaces a placeholder`() = runTest {
        assertEquals("aXc", "a{b}c".simpleFormat2 { key -> "X".takeIf { key == "b" } })
    }

    @Test
    fun `keeps an unknown placeholder intact`() = runTest {
        assertEquals("a{b}c", "a{b}c".simpleFormat2 { null })
    }

    @Test
    fun `evaluates nested placeholders inside out`() = runTest {
        val values = mapOf("y" to "z", "xz" to "OK")

        assertEquals("OK", "{x{y}}".simpleFormat2 { key -> values[key] })
    }

    @Test
    fun `returns unbalanced input unchanged`() = runTest {
        assertEquals("a{b", "a{b".simpleFormat2 { "X" })
    }

    @Test
    fun `evaluates nesting up to the depth limit`() = runTest {
        val input = "{".repeat(32) + "k" + "}".repeat(32)

        assertEquals("k", input.simpleFormat2 { key -> key })
    }

    @Test
    fun `returns too deeply nested input unchanged`() = runTest {
        val shallow = "{".repeat(33) + "k" + "}".repeat(33)
        val deep = "{".repeat(20_000) + "k" + "}".repeat(20_000)

        assertEquals(shallow, shallow.simpleFormat2 { key -> key })
        assertEquals(deep, deep.simpleFormat2 { key -> key })
    }
}
