package com.artemchep.keyguard.feature.home.vault.util

import com.artemchep.keyguard.feature.home.vault.search.createSecret
import kotlin.test.Test
import kotlin.test.assertEquals

class ChangeTagsActionTest {
    @Test
    fun `aggregateCipherTags returns exact distinct union sorted ignoring case`() {
        val actual = aggregateCipherTags(
            listOf(
                createSecret(
                    id = "a",
                    tags = listOf("work", "Personal", "Work"),
                ),
                createSecret(
                    id = "b",
                    tags = listOf("alpha", "work", "beta"),
                ),
            ),
        )

        assertEquals(
            listOf(
                "alpha",
                "beta",
                "Personal",
                "work",
                "Work",
            ),
            actual,
        )
    }
}
