package com.artemchep.keyguard.ui.tags

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.tags.TagSuggestion
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.tag_items_count_plural
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.util.DividerColor
import org.jetbrains.compose.resources.pluralStringResource

/**
 * A tag that exists in the vault, offered while typing. The part that
 * matches the typed text is highlighted, followed by the number of
 * items that have the tag.
 *
 * @param onClick receives the chip's position in the window, so the
 *   added chip can fly from there.
 */
@Composable
internal fun TagSuggestionChip(
    modifier: Modifier = Modifier,
    suggestion: TagSuggestion,
    highlighted: Boolean,
    onClick: (Offset?) -> Unit,
) {
    val motionScheme = MaterialTheme.motionScheme
    val shape = MaterialTheme.shapes.small
    val containerColor by animateColorAsState(
        targetValue = if (highlighted) {
            MaterialTheme.colorScheme.tagChipContainer
        } else {
            Color.Transparent
        },
        animationSpec = motionScheme.fastEffectsSpec(),
    )
    val borderColor by animateColorAsState(
        targetValue = if (highlighted) MaterialTheme.colorScheme.primary else DividerColor,
        animationSpec = motionScheme.fastEffectsSpec(),
    )
    val position = remember { arrayOfNulls<Offset>(1) }
    Row(
        modifier = modifier
            .heightIn(min = ChipHeight)
            .onGloballyPositioned { coordinates ->
                position[0] = coordinates.positionInWindow()
            }
            .clip(shape)
            .background(containerColor)
            .border(Dp.Hairline, borderColor, shape)
            .clickable(role = Role.Button) {
                onClick(position[0])
            }
            .padding(
                start = 8.dp,
                end = 12.dp,
                top = 4.dp,
                bottom = 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TagSuggestionChipContent(
            suggestion = suggestion,
        )
    }
}

@Composable
private fun RowScope.TagSuggestionChipContent(
    suggestion: TagSuggestion,
) {
    Icon(
        modifier = Modifier
            .size(18.dp),
        imageVector = Icons.Outlined.Add,
        contentDescription = null,
    )
    Spacer(
        modifier = Modifier
            .width(8.dp),
    )
    val primary = MaterialTheme.colorScheme.primary
    val text = remember(suggestion, primary) {
        buildSuggestionText(
            suggestion = suggestion,
            matchStyle = SpanStyle(
                color = primary,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
    Text(
        modifier = Modifier
            .widthIn(max = ChipMaxWidth),
        text = text,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (suggestion.usage > 0) {
        val usageDescription = pluralStringResource(
            Res.plurals.tag_items_count_plural,
            suggestion.usage,
            suggestion.usage,
        )
        Spacer(
            modifier = Modifier
                .width(8.dp),
        )
        Text(
            modifier = Modifier
                .clearAndSetSemantics {
                    contentDescription = usageDescription
                },
            text = suggestion.usage.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
    }
}

private fun buildSuggestionText(
    suggestion: TagSuggestion,
    matchStyle: SpanStyle,
): AnnotatedString = buildAnnotatedString {
    val match = suggestion.match
    if (match == null) {
        append(suggestion.name)
        return@buildAnnotatedString
    }
    append(suggestion.name.substring(0, match.first))
    withStyle(matchStyle) {
        append(suggestion.name.substring(match.first, match.last + 1))
    }
    append(suggestion.name.substring(match.last + 1))
}
