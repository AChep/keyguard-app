package com.artemchep.keyguard.feature.home.vault.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

interface IndexedText {
    companion object {
        operator fun invoke(text: String): IndexedText = IndexedTextImpl(text)
    }

    val text: String
    val components: List<Component>

    /**
     * The searchable component of the text, usually
     * a text between spaces.
     */
    data class Component(
        val origin: String,
    ) {
        val lowercase: String = origin.lowercase()
    }

    data class FindResult(
        override val text: String,
        override val components: List<Component>,
        val highlightedText: AnnotatedString,
        val score: Float,
    ) : IndexedText
}

private data class IndexedTextImpl(
    override val text: String,
) : IndexedText {
    override val components = text
        .split(' ')
        .map { str ->
            IndexedText.Component(
                origin = str,
            )
        }
}

fun IndexedText.find(
    query: IndexedText,
    colorBackground: Color = Color.Unspecified,
    colorContent: Color = Color.Unspecified,
    requireAll: Boolean = true,
): IndexedText.FindResult? {
    if (components.isEmpty()) {
        return null
    }

    val x = MutableList(query.components.size) { false }

    var score = 0f
    val text = buildAnnotatedString {
        components.forEachIndexed { compIndex, comp ->
            if (compIndex > 0) {
                append(' ')
            }
            append(comp.origin)

            var max = 0f
            var start = 0
            var end = 0
            query.components.forEachIndexed { queryCompIndex, queryComp ->
                if (comp.lowercase.isEmpty()) {
                    return@forEachIndexed
                }
                // Find the query in the target text
                val i = comp.lowercase.indexOf(queryComp.lowercase)
                if (i == -1) {
                    return@forEachIndexed
                }

                // We want to prefer results that start with a given query
                val queryPositionScore = if (i == 0) { // starts with a query
                    1f
                } else {
                    val penalty = 0.25f
                    val ratio = i.toFloat() / comp.lowercase.length.toFloat()
                    (1f - penalty) * (1f - ratio)
                }
                val queryLengthScore = queryComp.lowercase.length.toFloat()
                val totalScore = 0f +
                        queryPositionScore *
                        queryLengthScore
                if (totalScore > max) {
                    max = totalScore
                    // remember the position of the match, so we can draw it.
                    start = i
                    end = i + queryComp.lowercase.length
                }

                x[queryCompIndex] = true
            }
            if (start != 0 || end != 0) {
                val offset = length - comp.lowercase.length
                addStyle(
                    SpanStyle(
                        fontWeight = FontWeight.Bold,
                        background = colorBackground,
                        color = colorContent,
                    ),
                    start = offset + start,
                    end = offset + end,
                )
            }

            val componentPositionScoreModifier = kotlin.run {
                val compIndexNormalized = compIndex.toFloat() / components.size.toFloat()
                (1f - compIndexNormalized) * 0.5f + 0.5f
            }
            score += max * componentPositionScoreModifier
        }

        if (score < 0.01f || requireAll && !x.all { it }) return null
    }

    return IndexedText.FindResult(
        text = this.text,
        components = this.components,
        highlightedText = text,
        score = score / this.text.length.toFloat(),
    )
}
