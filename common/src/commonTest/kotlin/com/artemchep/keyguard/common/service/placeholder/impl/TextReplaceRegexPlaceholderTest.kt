package com.artemchep.keyguard.common.service.placeholder.impl

import com.artemchep.keyguard.common.io.bind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TextReplaceRegexPlaceholderTest {
    private val placeholder = TextReplaceRegexPlaceholder()

    @Test
    fun `replaces using documented example`() = runTest {
        assertEquals(
            "example.com",
            replace("t-replace-rx:/username@example.com/.*@(.*)/\$1/"),
        )
    }

    @Test
    fun `keeps separators inside the substituted value`() = runTest {
        assertEquals(
            "https://example.com/U/l",
            replace("t-replace-rx:/https://example.com/u/l/u/U/"),
        )
    }

    private suspend fun replace(key: String): String? =
        requireNotNull(placeholder.get(key)).bind()
}
