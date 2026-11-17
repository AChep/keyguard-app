package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.WordlistWordError
import kotlinx.collections.immutable.toImmutableList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadWordlistUtilTest {
    @Test
    fun `sequence parser filters blank and commented lines`() {
        val actual = with(ReadWordlistUtil) {
            sequenceOf(
                "",
                "   ",
                "# comment",
                "; comment",
                "- comment",
                "/ comment",
                "alpha",
                "beta",
            ).parseAsWordlist()
        }

        assertEquals(
            listOf("alpha", "beta").toImmutableList(),
            actual,
        )
    }

    @Test
    fun `string parser delegates to sequence parser`() {
        val content = """
            # comment
            alpha

            beta
        """.trimIndent()

        val actual = with(content) {
            ReadWordlistUtil.parseAsWordlist()
        }

        val expected = with(ReadWordlistUtil) {
            sequenceOf("# comment", "alpha", "", "beta").parseAsWordlist()
        }

        assertEquals(expected, actual)
    }

    @Test
    fun `a word must not be blank, too long or span lines`() {
        val longest = "a".repeat(ReadWordlistUtil.MAX_WORD_LENGTH)

        assertNull(ReadWordlistUtil.wordErrorOrNull("cat"))
        assertNull(ReadWordlistUtil.wordErrorOrNull(longest))
        assertEquals(WordlistWordError.BLANK, ReadWordlistUtil.wordErrorOrNull(" "))
        assertEquals(WordlistWordError.TOO_LONG, ReadWordlistUtil.wordErrorOrNull(longest + "a"))
        assertEquals(WordlistWordError.LINE_BREAK, ReadWordlistUtil.wordErrorOrNull("c\nat"))
        assertEquals(WordlistWordError.LINE_BREAK, ReadWordlistUtil.wordErrorOrNull("c\rat"))
    }

    @Test
    fun `a change must bring a new word or remove one`() {
        val existing = setOf("cat", "dog")

        fun errorOf(remove: Set<String> = emptySet(), add: List<String> = emptyList()) =
            ReadWordlistUtil.patchErrorOrNull(existing, remove, add)

        assertNull(errorOf(add = listOf("cat", "ant")))
        assertNull(errorOf(remove = setOf("cat")))
        // Replacing a word with itself is fine.
        assertNull(errorOf(remove = setOf("cat"), add = listOf("cat")))
        assertEquals(WordlistWordError.BLANK, errorOf())
        assertEquals(WordlistWordError.EXISTS, errorOf(add = listOf("cat", "cat")))
        assertEquals(WordlistWordError.EXISTS, errorOf(remove = setOf("cat"), add = listOf("dog")))
        assertEquals(WordlistWordError.NO_NEW_WORDS, errorOf(add = listOf("cat", "dog")))
    }
}
