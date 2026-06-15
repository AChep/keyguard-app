package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.QueryHighlightRole
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.QueryHighlightSpan
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.QueryHighlighting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SearchQueryHighlightVisualTransformationTest {
    private val colors = SearchQueryHighlightColors(
        textClause = Color.Red,
        facetClause = Color.Green,
        booleanClause = Color.Blue,
        unsupportedQualifier = Color.Gray,
        diagnostic = Color.Yellow,
    )

    @Test
    fun `visual transformation preserves text and offset mapping`() {
        val transformation = SearchQueryHighlightVisualTransformation(
            highlighting = QueryHighlighting(
                spans = listOf(
                    QueryHighlightSpan(
                        start = 0,
                        end = 8,
                        role = QueryHighlightRole.TextClause,
                    ),
                ),
            ),
            colors = colors,
        )
        val input = AnnotatedString("username")

        val transformed = transformation.filter(input)

        assertEquals(input.text, transformed.text.text)
        assertEquals(4, transformed.offsetMapping.originalToTransformed(4))
        assertEquals(4, transformed.offsetMapping.transformedToOriginal(4))
    }

    @Test
    fun `visual transformation applies semantic and overlay styles`() {
        val transformation = SearchQueryHighlightVisualTransformation(
            highlighting = QueryHighlighting(
                spans = listOf(
                    QueryHighlightSpan(
                        start = 0,
                        end = 10,
                        role = QueryHighlightRole.TextClause,
                    ),
                    QueryHighlightSpan(
                        start = 0,
                        end = 1,
                        role = QueryHighlightRole.Negation,
                    ),
                    QueryHighlightSpan(
                        start = 5,
                        end = 11,
                        role = QueryHighlightRole.QuotedValue,
                    ),
                    QueryHighlightSpan(
                        start = 0,
                        end = 7,
                        role = QueryHighlightRole.UnsupportedQualifier,
                    ),
                    QueryHighlightSpan(
                        start = 5,
                        end = 11,
                        role = QueryHighlightRole.Diagnostic,
                    ),
                ),
            ),
            colors = colors,
        )

        val transformed = transformation.filter(AnnotatedString("-note:\"oops"))
        val spanStyles = transformed.text.spanStyles

        val textClause = spanStyles.find {
            it.start == 0 &&
                it.end == 10 &&
                it.item.color == colors.textClause
        }
        val negation = spanStyles.find { it.start == 0 && it.end == 1 && it.item.fontWeight == FontWeight.Medium }
        val quoted = spanStyles.find { it.start == 5 && it.end == 11 && it.item.fontStyle == FontStyle.Italic }
        val unsupported = spanStyles.find {
            it.start == 0 &&
                it.end == 7 &&
                it.item.color == colors.unsupportedQualifier
        }
        val diagnostic = spanStyles.find {
            it.start == 5 &&
            it.end == 11 &&
                it.item.color == colors.diagnostic &&
                it.item.textDecoration == TextDecoration.Underline
        }

        assertNotNull(textClause)
        assertEquals(FontWeight.Medium, textClause.item.fontWeight)
        assertEquals(Color.Unspecified, textClause.item.background)
        assertNotNull(negation)
        assertEquals(Color.Unspecified, negation.item.color)
        assertEquals(Color.Unspecified, negation.item.background)
        assertNotNull(quoted)
        assertEquals(Color.Unspecified, quoted.item.color)
        assertEquals(Color.Unspecified, quoted.item.background)
        assertNotNull(unsupported)
        assertEquals(FontWeight.Medium, unsupported.item.fontWeight)
        assertEquals(Color.Unspecified, unsupported.item.background)
        assertNotNull(diagnostic)
        assertEquals(FontWeight.Medium, diagnostic.item.fontWeight)
        assertEquals(Color.Unspecified, diagnostic.item.background)
    }
}
