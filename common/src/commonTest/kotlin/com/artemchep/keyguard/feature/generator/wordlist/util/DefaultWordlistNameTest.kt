package com.artemchep.keyguard.feature.generator.wordlist.util

import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultWordlistNameTest {
    @Test
    fun dropsTheExtension() {
        assertEquals("eff_large_wordlist", defaultWordlistName("eff_large_wordlist.txt"))
    }

    @Test
    fun trimsTheName() {
        assertEquals("words", defaultWordlistName(" words.txt "))
    }

    @Test
    fun isEmptyWithoutAName() {
        assertEquals("", defaultWordlistName(null))
        assertEquals("", defaultWordlistName("  "))
    }
}
