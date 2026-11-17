package com.artemchep.autotype

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyboardInputLanguageTest {
    @Test
    fun `blank input is unknown`() {
        assertNull(normalizeKeyboardInputLanguage(""))
        assertNull(normalizeKeyboardInputLanguage("  "))
    }

    @Test
    fun `language tags resolve to their language`() {
        val cases = mapOf(
            "en" to "en",
            "ru-RU" to "ru",
            "zh-Hans" to "zh",
            "sr-Latn" to "sr",
            "en_US" to "en",
            "EN-us" to "en",
            " uk-UA " to "uk",
            "yue-Hant" to "yue",
        )
        for ((raw, expected) in cases) {
            assertEquals(expected, normalizeKeyboardInputLanguage(raw), raw)
        }
    }

    @Test
    fun `undetermined language tag is unknown`() {
        assertNull(normalizeKeyboardInputLanguage("und"))
    }

    @Test
    fun `XKB group names resolve to their language`() {
        val cases = mapOf(
            "Russian" to "ru",
            "English (US)" to "en",
            "French (Canada)" to "fr",
            "Serbian (Cyrillic)" to "sr",
            "German (Switzerland)" to "de",
            "Ukrainian" to "uk",
            "Kurdish, Latin Q" to "ku",
            "Russian (Ukraine, standard RSTU)" to "ru",
            "russian" to "ru",
            "Lao" to "lo",
        )
        for ((raw, expected) in cases) {
            assertEquals(expected, normalizeKeyboardInputLanguage(raw), raw)
        }
    }

    @Test
    fun `Hebrew resolves to one code from every source`() {
        assertEquals("he", normalizeKeyboardInputLanguage("Hebrew"))
        assertEquals("he", normalizeKeyboardInputLanguage("he"))
        assertEquals("he", normalizeKeyboardInputLanguage("iw"))
    }

    @Test
    fun `unknown XKB group names are unknown`() {
        assertNull(normalizeKeyboardInputLanguage("A user-defined custom Layout"))
        assertNull(normalizeKeyboardInputLanguage("Klingon"))
        assertNull(normalizeKeyboardInputLanguage("(US)"))
    }
}
