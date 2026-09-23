package com.artemchep.keyguard.common.util

import kotlin.test.Test
import kotlin.test.assertEquals

class StringSurrogateTest {
    @Test
    fun `takes a single bmp character`() {
        assertEquals("a", "abc".nextSymbol())
        assertEquals("b", "abc".nextSymbol(index = 1))
    }

    @Test
    fun `returns empty string out of bounds`() {
        assertEquals("", "".nextSymbol())
        assertEquals("", "abc".nextSymbol(index = 3))
        assertEquals("", "abc".nextSymbol(index = -1))
    }

    @Test
    fun `keeps a surrogate pair together`() {
        assertEquals(GRINNING_FACE, "${GRINNING_FACE}x".nextSymbol())
        assertEquals(GRINNING_FACE, "x$GRINNING_FACE".nextSymbol(index = 1))
    }

    @Test
    fun `takes only the first regional indicator of a flag`() {
        assertEquals("\uD83C\uDDFA", FLAG_US.nextSymbol())
    }

    @Test
    fun `drops the skin tone modifier`() {
        assertEquals("\uD83D\uDC4D", THUMBS_UP_MEDIUM.nextSymbol())
    }

    @Test
    fun `keeps a zero width joiner sequence together`() {
        assertEquals(FAMILY, "${FAMILY}x".nextSymbol())
    }

    @Test
    fun `keeps a variation selector and the symbol after it`() {
        assertEquals(RED_HEART, RED_HEART.nextSymbol())
        assertEquals(KEYCAP_ONE, "${KEYCAP_ONE}x".nextSymbol())
        // The symbol after a control character is taken too.
        assertEquals("${RED_HEART}x", "${RED_HEART}xy".nextSymbol())
    }

    @Test
    fun `handles unbounded control characters`() {
        val zwj = "\u200D".repeat(20_000)

        assertEquals(zwj, zwj.nextSymbol())
        assertEquals("a${zwj}b", "a${zwj}bc".nextSymbol())
    }

    private companion object {
        private const val GRINNING_FACE = "\uD83D\uDE00"
        private const val FLAG_US = "\uD83C\uDDFA\uD83C\uDDF8"
        private const val THUMBS_UP_MEDIUM = "\uD83D\uDC4D\uD83C\uDFFD"
        private const val FAMILY = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        private const val RED_HEART = "\u2764\uFE0F"
        private const val KEYCAP_ONE = "1\uFE0F\u20E3"
    }
}
