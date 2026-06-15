package com.artemchep.keyguard.common.usecase.impl

import kotlinx.collections.immutable.toImmutableList
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
