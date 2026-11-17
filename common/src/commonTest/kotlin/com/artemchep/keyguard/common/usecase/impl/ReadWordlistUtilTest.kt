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

    @Test
    fun `sequence parser strips dice numbers`() {
        assertEquals(listOf("abacus", "abdomen"), parse("11111\tabacus", "11112\tabdomen"))
        assertEquals(listOf("acid", "acorn"), parse("1111\tacid", "1112\tacorn"))
        assertEquals(listOf("alpha", "beta"), parse("11111  alpha", "11112 beta"))
        assertEquals(listOf("alpha", "beta"), parse("11111\talpha  ", "11112\tbeta\t"))
        // A diceware word may look like a comment.
        assertEquals(listOf("#", "//", "-", ";"), parse("66661\t#", "66662\t//", "66663\t-", "66664\t;"))
    }

    @Test
    fun `sequence parser skips the pgp envelope of a signed diceware list`() {
        val actual = parse(
            "-----BEGIN PGP SIGNED MESSAGE-----",
            "Hash: SHA256",
            "",
            "11111\talpha",
            "11112\tbeta",
            "11113\tgamma",
            "11114\tdelta",
            "11115\tepsilon",
            "11116\tzeta",
            "-----BEGIN PGP SIGNATURE-----",
            "Version: Test 1.0",
            "",
            "aGVsbG8gd29ybGQgdGhpcyBpcyBub3QgYSByZWFsIHNpZ25hdHVyZQ==",
            "=AbCd",
            "-----END PGP SIGNATURE-----",
        )

        assertEquals(listOf("alpha", "beta", "gamma", "delta", "epsilon", "zeta"), actual)
    }

    @Test
    fun `sequence parser keeps lines without dice numbers as is`() {
        // Only a few lines have dice numbers.
        assertEquals(
            listOf("alpha", "beta", "11111 gamma", "12345"),
            parse("alpha", "beta", "11111 gamma", "12345"),
        )
        // The numbers are not dice numbers.
        assertEquals(
            listOf("70000 alpha", "11110 beta", "1111a gamma"),
            parse("70000 alpha", "11110 beta", "1111a gamma"),
        )
    }

    @Test
    fun `sequence parser strips the byte order mark`() {
        assertEquals(listOf("alpha", "beta"), parse("\uFEFFalpha", "beta"))
        assertEquals(listOf("alpha", "beta"), parse("\uFEFF11111\talpha", "11112\tbeta"))
    }

    private fun parse(vararg lines: String) = with(ReadWordlistUtil) {
        lines.asSequence().parseAsWordlist()
    }
}
