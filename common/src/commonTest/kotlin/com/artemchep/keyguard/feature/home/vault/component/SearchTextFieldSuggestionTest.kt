package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.artemchep.keyguard.feature.home.vault.search.query.VaultSearchQualifierApplyResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SearchTextFieldSuggestionTest {
    @Test
    fun `tab acceptance places cursor after qualifier`() {
        val result = acceptSearchQualifierSuggestion(
            value = TextFieldValue(
                text = "fie",
                selection = TextRange(3),
            ),
            onQualifierSuggestion = {
                VaultSearchQualifierApplyResult(
                    text = "field:",
                    cursor = 6,
                )
            },
        )

        assertEquals(
            TextFieldValue(
                text = "field:",
                selection = TextRange(6),
            ),
            result,
        )
    }

    @Test
    fun `click acceptance places cursor after qualifier`() {
        val result = acceptSearchQualifierSuggestion(
            value = TextFieldValue(
                text = "foo fie",
                selection = TextRange(7),
            ),
            onQualifierSuggestion = {
                VaultSearchQualifierApplyResult(
                    text = "foo field:",
                    cursor = 10,
                )
            },
        )

        assertEquals(
            TextFieldValue(
                text = "foo field:",
                selection = TextRange(10),
            ),
            result,
        )
    }

    @Test
    fun `missing apply result keeps field unchanged`() {
        val result = acceptSearchQualifierSuggestion(
            value = TextFieldValue(
                text = "fie",
                selection = TextRange(3),
            ),
            onQualifierSuggestion = { null },
        )

        assertNull(result)
    }

    @Test
    fun `does not accept qualifier suggestion when caret is not at end`() {
        var invoked = false
        val result = acceptSearchQualifierSuggestion(
            value = TextFieldValue(
                text = "foo fie",
                selection = TextRange(4),
            ),
            onQualifierSuggestion = {
                invoked = true
                VaultSearchQualifierApplyResult(
                    text = "foo field:",
                    cursor = 10,
                )
            },
        )

        assertNull(result)
        assertFalse(invoked)
    }
}
