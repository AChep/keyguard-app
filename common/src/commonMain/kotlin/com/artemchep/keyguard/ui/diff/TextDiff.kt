package com.artemchep.keyguard.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.text_diff_added_line
import com.artemchep.keyguard.res.text_diff_create_empty_file
import com.artemchep.keyguard.res.text_diff_line_endings
import com.artemchep.keyguard.res.text_diff_loading
import com.artemchep.keyguard.res.text_diff_new_line
import com.artemchep.keyguard.res.text_diff_no_changes
import com.artemchep.keyguard.res.text_diff_no_newline
import com.artemchep.keyguard.res.text_diff_original_line
import com.artemchep.keyguard.res.text_diff_remove_empty_file
import com.artemchep.keyguard.res.text_diff_removed_line
import com.artemchep.keyguard.res.text_diff_simplified
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.horizontalPaddingFourth
import com.artemchep.keyguard.ui.theme.horizontalPaddingHalf
import com.artemchep.keyguard.ui.theme.okContainer
import com.artemchep.keyguard.ui.theme.onOkContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun rememberTextDiff(path: String, before: String?, after: String?): PreparedTextDiff? {
    // Key the state itself so a refresh never displays or copies the previous review while work starts.
    var result by remember(path, before, after) { mutableStateOf<PreparedTextDiff?>(null) }
    LaunchedEffect(path, before, after) {
        result = withContext(Dispatchers.Default) {
            val model = buildTextDiff(before, after)
            PreparedTextDiff(model, model.patch(path))
        }
    }
    return result
}

/** A service-free, selectable unified diff. Clipboard access belongs to the caller. */
@Composable
internal fun TextDiff(
    model: TextDiffModel?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            if (model == null) {
                Row(
                    modifier = Modifier.padding(Dimens.contentPadding),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.contentPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KeyguardLoadingIndicator()
                    Text(stringResource(Res.string.text_diff_loading))
                }
            } else {
                TextDiffNotes(model)
                val numberWidth = textDiffNumberWidth(model)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    SelectionContainer {
                        Column(Modifier.padding(bottom = Dimens.contentPadding)) {
                            model.hunks.forEach { hunk ->
                                TextDiffHunk(hunk, model.showLineEndings, numberWidth)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextDiffNotes(model: TextDiffModel) {
    val messages = buildList {
        if (model.hunks.isEmpty()) add(when {
            !model.beforeExists && model.afterExists -> Res.string.text_diff_create_empty_file
            model.beforeExists && !model.afterExists -> Res.string.text_diff_remove_empty_file
            else -> Res.string.text_diff_no_changes
        })
        if (model.simplified) add(Res.string.text_diff_simplified)
        if (model.showLineEndings) add(Res.string.text_diff_line_endings)
    }
    messages.forEach { message ->
        Text(
            text = stringResource(message),
            modifier = Modifier.padding(horizontal = Dimens.contentPadding, vertical = Dimens.horizontalPaddingFourth),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TextDiffHunk(hunk: DiffHunk, showLineEndings: Boolean, numberWidth: Dp) {
    Text(
        text = hunk.header,
        modifier = Modifier.padding(Dimens.contentPadding),
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    var before = hunk.beforeStart
    var after = hunk.afterStart
    hunk.lines.forEach { line ->
        val originalLine = if (line.kind == DiffLineKind.ADDED) null else before++
        val newLine = if (line.kind == DiffLineKind.REMOVED) null else after++
        TextDiffLine(line, showLineEndings, originalLine, newLine, numberWidth, hunk)
        if (line.source.ending == DiffLineEnding.NONE) {
            Text(
                text = stringResource(Res.string.text_diff_no_newline),
                modifier = Modifier.padding(horizontal = Dimens.contentPadding),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TextDiffLine(
    line: DiffLine,
    showLineEndings: Boolean,
    originalLine: Int?,
    newLine: Int?,
    numberWidth: Dp,
    hunk: DiffHunk,
) {
    val colors = MaterialTheme.colorScheme
    val background = when (line.kind) {
        DiffLineKind.ADDED -> colors.okContainer
        DiffLineKind.REMOVED -> colors.errorContainer
        DiffLineKind.CONTEXT -> colors.surfaceContainer
    }
    val foreground = when (line.kind) {
        DiffLineKind.ADDED -> colors.onOkContainer
        DiffLineKind.REMOVED -> colors.onErrorContainer
        DiffLineKind.CONTEXT -> colors.onSurface
    }
    val description = when (line.kind) {
        DiffLineKind.ADDED -> stringResource(Res.string.text_diff_added_line)
        DiffLineKind.REMOVED -> stringResource(Res.string.text_diff_removed_line)
        DiffLineKind.CONTEXT -> null
    }
    val style = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        textDirection = TextDirection.Ltr,
    )
    Row(
        modifier = Modifier.fillMaxWidth().background(background)
            .semantics(mergeDescendants = true) { description?.let { stateDescription = it } }
            .padding(horizontal = Dimens.contentPadding, vertical = Dimens.horizontalPaddingFourth),
    ) {
        TextDiffLineNumbers(
            originalLine, newLine, numberWidth, foreground, hunk,
        )
        Text(
            text = line.kind.prefix.toString(),
            modifier = Modifier.widthIn(min = Dimens.contentPadding).padding(end = Dimens.horizontalPaddingFourth),
            style = style,
            color = foreground,
        )
        Text(text = line.source.text, modifier = Modifier.weight(1f), style = style, color = foreground)
        if (showLineEndings && line.kind != DiffLineKind.CONTEXT && line.source.ending != DiffLineEnding.NONE) {
            Text(
                text = line.source.ending.name,
                modifier = Modifier.padding(start = Dimens.horizontalPaddingFourth),
                style = MaterialTheme.typography.labelSmall,
                color = foreground,
            )
        }
    }
}

@Composable
private fun textDiffNumberWidth(model: TextDiffModel): Dp {
    val digits = remember(model) {
        model.hunks.maxOfOrNull { maxOf(it.beforeStart + it.beforeCount, it.afterStart + it.afterCount) - 1 }
            ?.coerceAtLeast(1)?.toString()?.length ?: 1
    }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val width = measurer.measure("9".repeat(digits), style = style, softWrap = false).size.width
    return with(LocalDensity.current) { width.toDp() }
}

@Composable
private fun TextDiffLineNumbers(
    originalLine: Int?,
    newLine: Int?,
    width: Dp,
    color: Color,
    hunk: DiffHunk,
) {
    val originalDescription = originalLine?.let { stringResource(Res.string.text_diff_original_line, it) }
    val newDescription = newLine?.let { stringResource(Res.string.text_diff_new_line, it) }
    val numbers = buildList {
        if (hunk.beforeCount > 0) add(originalLine)
        if (hunk.afterCount > 0) add(newLine)
    }
    val style = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace,
        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
        textDirection = TextDirection.Ltr,
    )
    DisableSelection {
        Row(
            modifier = Modifier.padding(end = Dimens.horizontalPaddingHalf)
                .clearAndSetSemantics {
                    contentDescription = listOfNotNull(originalDescription, newDescription).joinToString(", ")
                },
            horizontalArrangement = Arrangement.spacedBy(Dimens.horizontalPaddingHalf),
        ) {
            numbers.forEach { number ->
                Text(
                    text = number?.toString().orEmpty(),
                    modifier = Modifier.width(width),
                    style = style,
                    color = color,
                    textAlign = TextAlign.End,
                    softWrap = false,
                )
            }
        }
    }
}
