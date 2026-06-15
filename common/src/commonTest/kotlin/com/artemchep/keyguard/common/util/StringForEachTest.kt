package com.artemchep.keyguard.common.util

import kotlin.test.Test
import kotlin.test.assertEquals

class StringForEachTest {
    @Test
    fun `splits ascii into single character strings`() {
        val result = "abc".asCodePointsSequence().toList()

        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `keeps bmp non ascii characters as single strings`() {
        val result = "a\u00E9\u65E5".asCodePointsSequence().toList()

        assertEquals(listOf("a", "\u00E9", "\u65E5"), result)
    }

    @Test
    fun `keeps supplementary characters as single strings`() {
        val emoji = "\uD83D\uDE00"

        val result = emoji.asCodePointsSequence().toList()

        assertEquals(listOf(emoji), result)
    }

    @Test
    fun `preserves order for mixed code points`() {
        val emoji = "\uD83D\uDE00"

        val result = "a${emoji}\u00E9".asCodePointsSequence().toList()

        assertEquals(listOf("a", emoji, "\u00E9"), result)
    }

    @Test
    fun `yields unmatched surrogates individually`() {
        val highSurrogate = "\uD83D"
        val lowSurrogate = "\uDE00"

        val result = "${highSurrogate}x${lowSurrogate}".asCodePointsSequence().toList()

        assertEquals(listOf(highSurrogate, "x", lowSurrogate), result)
    }
}
