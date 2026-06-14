package com.artemchep.keyguard.android.autofill.v2.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AhoCorasickAutomatonTest {
    @Test
    fun `single pattern match returns its tag`() {
        val automaton = automatonOf("needle" to TAG_A)

        assertEquals(TAG_A, automaton.match("haystack needle haystack"))
    }

    @Test
    fun `non matching text returns zero`() {
        val automaton = automatonOf("needle" to TAG_A)

        assertEquals(0L, automaton.match("haystack only"))
    }

    @Test
    fun `multiple matched patterns are ORed together`() {
        val automaton =
            automatonOf(
                "user" to TAG_A,
                "mail" to TAG_B,
                "otp" to TAG_C,
            )

        assertEquals(TAG_A or TAG_B, automaton.match("preferred user mail field"))
    }

    @Test
    fun `duplicate pattern additions OR their tags`() {
        val automaton =
            AhoCorasickAutomaton
                .Builder()
                .addPattern("login", TAG_A)
                .addPattern("login", TAG_B)
                .build()

        assertEquals(TAG_A or TAG_B, automaton.match("login"))
    }

    @Test
    fun `addAll applies the same tag to any matching keyword`() {
        val automaton =
            AhoCorasickAutomaton
                .Builder()
                .addAll(
                    listOf("login", "signin", "sign in"),
                    TAG_C,
                )
                .build()

        assertEquals(TAG_C, automaton.match("tap sign in to continue"))
    }

    @Test
    fun `overlapping patterns all contribute to the result`() {
        val automaton =
            automatonOf(
                "he" to TAG_A,
                "she" to TAG_B,
                "hers" to TAG_C,
            )

        assertEquals(TAG_A or TAG_B or TAG_C, automaton.match("ushers"))
    }

    @Test
    fun `failure link propagation reports suffix keyword matches`() {
        val automaton =
            automatonOf(
                "abcd" to TAG_A,
                "bcd" to TAG_B,
                "cd" to TAG_C,
            )

        assertEquals(TAG_A or TAG_B or TAG_C, automaton.match("xxabcdyy"))
    }

    @Test
    fun `scanner recovers after mismatch and continues matching`() {
        val automaton =
            automatonOf(
                "abcd" to TAG_A,
                "bce" to TAG_B,
            )

        assertEquals(TAG_B, automaton.match("abce"))
    }

    @Test
    fun `repeated matches do not change the final bitmask beyond the matched tag`() {
        val automaton = automatonOf("ha" to TAG_A)

        assertEquals(TAG_A, automaton.match("hahaha"))
    }

    @Test
    fun `empty builder returns zero for any text`() {
        val automaton = AhoCorasickAutomaton.Builder().build()

        assertEquals(0L, automaton.match("any text"))
    }

    @Test
    fun `empty text without empty patterns returns zero`() {
        val automaton =
            automatonOf(
                "login" to TAG_A,
                "password" to TAG_B,
            )

        assertEquals(0L, automaton.match(""))
    }

    @Test
    fun `embedded match is found despite unrelated prefix and suffix`() {
        val automaton = automatonOf("token" to TAG_B)

        assertEquals(TAG_B, automaton.match("prefix-token-suffix"))
    }

    @Test
    fun `ascii token pattern does not match inside a larger token`() {
        val automaton =
            AhoCorasickAutomaton
                .Builder()
                .addPattern(
                    pattern = "pin",
                    tag = TAG_A,
                    matchMode = AhoCorasickAutomaton.MatchMode.ASCII_TOKEN,
                ).build()

        assertEquals(0L, automaton.match("shipping"))
    }

    @Test
    fun `ascii token pattern matches after identifier normalization`() {
        val automaton =
            AhoCorasickAutomaton
                .Builder()
                .addPattern(
                    pattern = "pin",
                    tag = TAG_A,
                    matchMode = AhoCorasickAutomaton.MatchMode.ASCII_TOKEN,
                ).build()

        assertEquals(TAG_A, automaton.match(normalizeSignalText("pinCode")))
        assertEquals(TAG_A, automaton.match(normalizeSignalText("pin-code")))
        assertEquals(TAG_A, automaton.match(normalizeSignalText("pin_1")))
    }

    private fun automatonOf(vararg patterns: Pair<String, Long>): AhoCorasickAutomaton =
        AhoCorasickAutomaton
            .Builder()
            .apply {
                patterns.forEach { (pattern, tag) ->
                    addPattern(pattern, tag)
                }
            }
            .build()

    private companion object {
        const val TAG_A = 1L shl 0
        const val TAG_B = 1L shl 1
        const val TAG_C = 1L shl 2
    }
}
