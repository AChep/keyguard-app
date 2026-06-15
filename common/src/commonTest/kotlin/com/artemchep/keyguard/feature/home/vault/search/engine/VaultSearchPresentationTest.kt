package com.artemchep.keyguard.feature.home.vault.search.engine

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.feature.home.vault.search.createSecret
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultTextField
import kotlin.test.Test
import kotlin.test.assertEquals

class VaultSearchPresentationTest {
    @Test
    fun `highlight title maps lowercase expansion back to original grapheme`() {
        val highlighted =
            highlightTitle(
                text = "İpek",
                terms = setOf("ipek"),
                highlightBackgroundColor = Color.Unspecified,
                highlightContentColor = Color.Unspecified,
            )

        assertEquals("İpek", highlighted.text)
        assertEquals(1, highlighted.spanStyles.size)
        assertEquals(0, highlighted.spanStyles.single().start)
        assertEquals("İpek".length, highlighted.spanStyles.single().end)
    }

    @Test
    fun `snippet truncation preserves emoji grapheme clusters`() {
        val familyEmoji = "👨‍👩‍👧‍👦"
        val value = familyEmoji.repeat(70)

        val snippet =
            snippetForField(
                field = VaultTextField.Note,
                source = createSecret(id = "emoji-id"),
                value = value,
            )

        assertEquals(familyEmoji.repeat(64), snippet)
    }

    @Test
    fun `snippet truncation preserves combining character grapheme clusters`() {
        val grapheme = "a\u0301"
        val value = grapheme.repeat(70)

        val snippet =
            snippetForField(
                field = VaultTextField.Note,
                source = createSecret(id = "accent-id"),
                value = value,
            )

        assertEquals(grapheme.repeat(64), snippet)
    }

    @Test
    fun `password snippet masking remains unchanged`() {
        val snippet =
            snippetForField(
                field = VaultTextField.Password,
                source = createSecret(id = "password-id"),
                value = "super-secret",
            )

        assertEquals(HIDDEN_FIELD_MASK, snippet)
    }
}
