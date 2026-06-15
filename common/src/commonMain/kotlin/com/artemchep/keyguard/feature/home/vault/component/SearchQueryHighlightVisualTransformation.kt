package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.QueryHighlightRole
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.QueryHighlighting

@Immutable
internal data class SearchQueryHighlightColors(
    val textClause: Color,
    val facetClause: Color,
    val booleanClause: Color,
    val unsupportedQualifier: Color,
    val diagnostic: Color,
)

@Composable
internal fun rememberSearchQueryHighlightColors(): SearchQueryHighlightColors {
    val colorScheme = MaterialTheme.colorScheme
    return remember(colorScheme) {
        SearchQueryHighlightColors(
            textClause = colorScheme.primary,
            facetClause = colorScheme.secondary,
            booleanClause = colorScheme.tertiary,
            unsupportedQualifier = colorScheme.onSurfaceVariant,
            diagnostic = colorScheme.error,
        )
    }
}

@Composable
internal fun rememberSearchQueryHighlightVisualTransformation(
    highlighting: QueryHighlighting,
): VisualTransformation {
    if (highlighting.isEmpty) {
        return VisualTransformation.None
    }

    val colors = rememberSearchQueryHighlightColors()
    return remember(highlighting, colors) {
        SearchQueryHighlightVisualTransformation(
            highlighting = highlighting,
            colors = colors,
        )
    }
}

internal class SearchQueryHighlightVisualTransformation(
    internal val highlighting: QueryHighlighting,
    internal val colors: SearchQueryHighlightColors,
) : VisualTransformation {
    override fun filter(
        text: AnnotatedString,
    ): TransformedText {
        if (highlighting.isEmpty) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val styledText = buildAnnotatedString {
            append(text)
            highlighting.spans.forEach { span ->
                val start = span.start.coerceIn(0, length)
                val end = span.end.coerceIn(start, length)
                if (start == end) {
                    return@forEach
                }
                addStyle(
                    style = colors.renderSpecFor(span.role).toSpanStyle(),
                    start = start,
                    end = end,
                )
            }
        }
        return TransformedText(
            text = styledText,
            offsetMapping = OffsetMapping.Identity,
        )
    }
}

@Immutable
internal data class SearchQueryHighlightRenderSpec(
    val contentColor: Color = Color.Unspecified,
    val fontWeight: FontWeight? = null,
    val fontStyle: FontStyle? = null,
    val textDecoration: TextDecoration? = null,
) {
    fun toSpanStyle(): SpanStyle = SpanStyle(
        color = contentColor,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        textDecoration = textDecoration,
    )
}

internal fun SearchQueryHighlightColors.renderSpecFor(
    role: QueryHighlightRole,
): SearchQueryHighlightRenderSpec = when (role) {
    QueryHighlightRole.TextClause -> SearchQueryHighlightRenderSpec(
        contentColor = textClause,
        fontWeight = FontWeight.Medium,
    )

    QueryHighlightRole.FacetClause -> SearchQueryHighlightRenderSpec(
        contentColor = facetClause,
        fontWeight = FontWeight.Medium,
    )

    QueryHighlightRole.BooleanClause -> SearchQueryHighlightRenderSpec(
        contentColor = booleanClause,
        fontWeight = FontWeight.Medium,
    )

    QueryHighlightRole.Negation -> SearchQueryHighlightRenderSpec(
        fontWeight = FontWeight.Medium,
    )

    QueryHighlightRole.QuotedValue -> SearchQueryHighlightRenderSpec(
        fontStyle = FontStyle.Italic,
    )

    QueryHighlightRole.UnsupportedQualifier -> SearchQueryHighlightRenderSpec(
        contentColor = unsupportedQualifier,
        fontWeight = FontWeight.Medium,
    )

    QueryHighlightRole.Diagnostic -> SearchQueryHighlightRenderSpec(
        contentColor = diagnostic,
        fontWeight = FontWeight.Medium,
        textDecoration = TextDecoration.Underline,
    )
}
