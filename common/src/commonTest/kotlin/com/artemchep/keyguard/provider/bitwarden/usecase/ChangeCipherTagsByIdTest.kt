package com.artemchep.keyguard.provider.bitwarden.usecase

import kotlin.test.Test
import kotlin.test.assertEquals

class ChangeCipherTagsByIdTest {
    @Test
    fun `normalizeCipherTags trims drops blanks and keeps first exact occurrence order`() {
        val actual = normalizeCipherTags(
            listOf(
                " Work ",
                "",
                "Personal",
                "Work",
                "  ",
                "Personal",
                "work",
            ),
        )

        assertEquals(
            listOf(
                "Work",
                "Personal",
                "work",
            ),
            actual,
        )
    }
}
