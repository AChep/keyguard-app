package com.artemchep.keyguard.feature.home.vault.search.engine

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.home.vault.component.ObscureCharBlock
import com.artemchep.keyguard.feature.home.vault.component.obscureCardNumber
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultTextField

private const val MAX_SNIPPET_GRAPHEME_COUNT = 64
private val WHITESPACE_REGEX = "\\s+".toRegex()

internal fun snippetForField(
    field: VaultTextField,
    source: DSecret,
    value: String,
): String =
    when (field) {
        VaultTextField.Password -> {
            HIDDEN_FIELD_MASK
        }

        VaultTextField.CardNumber -> {
            obscureCardNumber(value)
        }

        VaultTextField.FieldName -> {
            findSearchableCustomFieldMatch(
                source = source,
                raw = value,
            )?.name
                ?.normalizeSnippet()
                ?: value.normalizeSnippet()
        }

        VaultTextField.Field -> {
            val match =
                findSearchableCustomFieldMatch(
                    source = source,
                    raw = value,
                )
            when {
                match?.value == value && match.field.type == DSecret.Field.Type.Hidden -> HIDDEN_FIELD_MASK
                match?.value == value -> value.normalizeSnippet()
                else -> match?.name?.normalizeSnippet()
                    ?: value.normalizeSnippet()
            }
        }

        VaultTextField.Ssh -> {
            if (source.sshKey?.privateKey == value) {
                HIDDEN_FIELD_MASK
            } else {
                value.normalizeSnippet()
            }
        }

        else -> {
            value
                .normalizeSnippet()
        }
    }

internal val HIDDEN_FIELD_MASK: String = ObscureCharBlock

private fun String.normalizeSnippet(): String =
    replace(WHITESPACE_REGEX, " ")
        .trim()
        .truncateGraphemeSafe(MAX_SNIPPET_GRAPHEME_COUNT)

internal fun highlightTitle(
    text: String,
    terms: Set<String>,
    highlightBackgroundColor: Color,
    highlightContentColor: Color,
): AnnotatedString {
    val foldedText = text.toFoldedTextMapping()
    val ranges = mutableListOf<IntRange>()
    terms.sortedByDescending(String::length).forEach { term ->
        val foldedTerm =
            normalizeSearchValue(
                value = term,
                foldAliases = true,
            ).folded
        if (foldedTerm.isBlank()) {
            return@forEach
        }
        var startIndex = foldedText.folded.indexOf(foldedTerm)
        while (startIndex >= 0) {
            val endIndex = startIndex + foldedTerm.length
            val originalStart = foldedText.startOffsets[startIndex]
            val originalEndExclusive = foldedText.endOffsets[endIndex - 1]
            if (originalStart < originalEndExclusive) {
                ranges += originalStart until originalEndExclusive
            }
            startIndex = foldedText.folded.indexOf(foldedTerm, endIndex)
        }
    }

    val merged =
        ranges
            .sortedBy(IntRange::first)
            .fold(mutableListOf<IntRange>()) { acc, range ->
                val last = acc.lastOrNull()
                if (last == null || range.first > last.last + 1) {
                    acc += range
                } else {
                    acc[acc.lastIndex] = last.first..maxOf(last.last, range.last)
                }
                acc
            }

    return buildAnnotatedString {
        append(text)
        merged.forEach { range ->
            addStyle(
                style =
                    SpanStyle(
                        fontWeight = FontWeight.Bold,
                        background = highlightBackgroundColor,
                        color = highlightContentColor,
                    ),
                start = range.first,
                end = range.last + 1,
            )
        }
    }
}

private data class FoldedTextMapping(
    val folded: String,
    val startOffsets: IntArray,
    val endOffsets: IntArray,
)

private fun String.toFoldedTextMapping(): FoldedTextMapping {
    if (isEmpty()) {
        return FoldedTextMapping(
            folded = "",
            startOffsets = IntArray(0),
            endOffsets = IntArray(0),
        )
    }

    val startOffsets = mutableListOf<Int>()
    val endOffsets = mutableListOf<Int>()
    val folded = buildString(length) {
        forEachPlatformGraphemeCluster(this@toFoldedTextMapping) { clusterStart, clusterEnd ->
            val cluster = this@toFoldedTextMapping.substring(clusterStart, clusterEnd)
            val foldedCluster =
                normalizeSearchValue(
                    value = cluster,
                    foldAliases = true,
                ).folded
            foldedCluster.forEach { char ->
                append(char)
                startOffsets += clusterStart
                endOffsets += clusterEnd
            }
        }
    }
    return FoldedTextMapping(
        folded = folded,
        startOffsets = startOffsets.toIntArray(),
        endOffsets = endOffsets.toIntArray(),
    )
}

private fun String.truncateGraphemeSafe(
    maxGraphemeCount: Int,
): String {
    if (length <= maxGraphemeCount) {
        return this
    }

    var clusterCount = 0
    var end = 0
    forEachPlatformGraphemeCluster(this) { _, clusterEnd ->
        if (clusterCount < maxGraphemeCount) {
            end = clusterEnd
            clusterCount += 1
        }
    }
    return if (clusterCount < maxGraphemeCount) {
        this
    } else {
        substring(0, end)
    }
}
