package com.artemchep.keyguard.ui.tags

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.tags.TagsFieldModel
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.tags_field_placeholder
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FlatTextFieldSurface
import com.artemchep.keyguard.ui.PlainTextField
import com.artemchep.keyguard.ui.rememberFieldBuffer
import com.artemchep.keyguard.ui.theme.LocalExpressive
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.util.DividerColor
import org.jetbrains.compose.resources.stringResource

private const val INPUT_LAYOUT_ID = "input"

/** The scale chips grow from and shrink to. */
private const val CHIP_ENTER_SCALE = 0.8f

internal val ChipHeight = 32.dp
internal val ChipMaxWidth = 240.dp
private val ChipSpacing = 8.dp
private val InputMinWidth = 96.dp
private val LineMinHeight = 50.dp

private val TagsKeyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Text,
    imeAction = ImeAction.Done,
)

/**
 * Edits a list of tags as chips with an inline input. Typing suggests the
 * tags that exist in the vault; a comma, a semicolon or the Enter key
 * turns the text into a chip.
 */
@Composable
fun TagsField(
    modifier: Modifier = Modifier,
    value: TagsFieldModel,
    shapeState: Int = ShapeState.ALL,
    expressive: Boolean = LocalExpressive.current,
) {
    val valueState = rememberUpdatedState(value)
    val buffer = rememberFieldBuffer(
        text = value.draft.text,
        textRevision = value.draft.textRevision,
    )
    val haptic = LocalHapticFeedback.current
    val controller = remember(buffer, haptic) {
        TagsFieldController(
            valueState = valueState,
            buffer = buffer,
            haptic = haptic,
        )
    }

    FlatTextFieldSurface(
        modifier = modifier,
        isError = false,
        isFocused = controller.fieldHasFocus,
        shapeState = shapeState,
        expressive = expressive,
    ) {
        Column(
            modifier = Modifier
                // The group stays focused while the focus
                // moves between the input and the chips.
                .onFocusChanged { state ->
                    controller.onFieldFocusChanged(state.hasFocus)
                }
                .focusGroup()
                .padding(
                    start = 16.dp,
                    end = 12.dp,
                    top = 8.dp,
                    bottom = 8.dp,
                ),
        ) {
            TagsFieldContent(
                controller = controller,
            )
            TagsFieldSuggestions(
                controller = controller,
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun TagsFieldContent(
    controller: TagsFieldController,
) {
    val motionScheme = MaterialTheme.motionScheme
    val chips = rememberLingeringList(controller.value.tags)
    LookaheadScope {
        val boundsTransform = remember(motionScheme) {
            BoundsTransform { _, _ ->
                motionScheme.defaultSpatialSpec()
            }
        }
        val boundsModifier = Modifier
            .animateBounds(
                lookaheadScope = this,
                boundsTransform = boundsTransform,
            )
        val editable = controller.editable
        TagsFlowLayout(
            modifier = Modifier
                .pointerInput(editable) {
                    if (!editable) return@pointerInput
                    // Tapping the empty space of the
                    // field focuses the input.
                    detectTapGestures {
                        controller.focusRequester.requestFocus()
                    }
                },
        ) {
            chips.forEach { entry ->
                key(entry.key) {
                    TagChipItem(
                        modifier = boundsModifier,
                        entry = entry,
                        controller = controller,
                    )
                }
            }
            if (editable) {
                TagsFieldInput(
                    modifier = Modifier
                        .layoutId(INPUT_LAYOUT_ID)
                        .then(boundsModifier),
                    controller = controller,
                )
            }
        }
    }
}

@Composable
private fun TagChipItem(
    modifier: Modifier = Modifier,
    entry: LingeringEntry,
    controller: TagsFieldController,
) {
    val motionScheme = MaterialTheme.motionScheme
    val tag = entry.key
    val exiting = !entry.visibility.targetState
    val flyFrom = remember {
        controller.takeFlyOrigin(tag)
    }
    AnimatedVisibility(
        modifier = modifier,
        visibleState = entry.visibility,
        // A picked suggestion flies into place
        // instead of popping up.
        enter = if (flyFrom != null) {
            EnterTransition.None
        } else {
            fadeIn(motionScheme.fastEffectsSpec()) +
                scaleIn(motionScheme.fastSpatialSpec(), initialScale = CHIP_ENTER_SCALE)
        },
        exit = fadeOut(motionScheme.fastEffectsSpec()) +
            scaleOut(motionScheme.fastSpatialSpec(), targetScale = CHIP_ENTER_SCALE),
    ) {
        val value = controller.value
        TagInputChip(
            text = tag,
            armed = controller.armed && !exiting && tag == value.tags.lastOrNull(),
            shake = controller.shake?.takeIf { it.tag == tag },
            flyFrom = flyFrom,
            onClick = if (value.onEdit != null && !exiting) {
                // lambda
                {
                    controller.edit(tag)
                }
            } else {
                null
            },
            onRemove = if (value.onRemove != null && !exiting) {
                // lambda
                {
                    controller.remove(tag)
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun TagsFieldInput(
    modifier: Modifier = Modifier,
    controller: TagsFieldController,
) {
    val completion = controller.completion
    val ghostColor = LocalContentColor.current
        .combineAlpha(DisabledEmphasisAlpha)
    val visualTransformation = remember(completion, ghostColor) {
        if (completion != null) {
            GhostTextTransformation(
                ghost = completion,
                color = ghostColor,
            )
        } else {
            VisualTransformation.None
        }
    }
    val placeholderText = stringResource(Res.string.tags_field_placeholder)
    val placeholder: (@Composable () -> Unit)? = if (controller.value.tags.isEmpty()) {
        // composable
        {
            Text(
                text = placeholderText,
                maxLines = 1,
            )
        }
    } else {
        null
    }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart,
    ) {
        PlainTextField(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(controller.focusRequester)
                .onFocusChanged { state ->
                    controller.onFocusChanged(state.isFocused)
                }
                .onPreviewKeyEvent { event ->
                    controller.onKeyEvent(event)
                },
            value = controller.buffer.value,
            placeholder = placeholder,
            visualTransformation = visualTransformation,
            keyboardOptions = TagsKeyboardOptions,
            keyboardActions = KeyboardActions(
                // Keep the keyboard open, so
                // the next tag can be typed.
                onDone = { controller.submit() },
            ),
            singleLine = true,
            maxLines = 1,
            onValueChange = controller::onValueChange,
        )
    }
}

@Composable
private fun TagsFieldSuggestions(
    controller: TagsFieldController,
) {
    ExpandedIfNotEmpty(
        valueOrNull = Unit.takeIf { controller.panelVisible },
    ) {
        Column {
            HorizontalDivider(
                modifier = Modifier
                    .padding(vertical = 8.dp),
                color = DividerColor,
            )
            FlowRow(
                modifier = Modifier
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(ChipSpacing),
                verticalArrangement = Arrangement.spacedBy(ChipSpacing),
            ) {
                val highlighted = controller.highlighted
                controller.value.suggestions.forEachIndexed { index, suggestion ->
                    key(suggestion.name) {
                        TagSuggestionChip(
                            suggestion = suggestion,
                            highlighted = index == highlighted,
                            onClick = { origin ->
                                controller.pick(suggestion.name, origin)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Lays out the chips in lines. The input, if any, takes the rest of
 * the last line, or a line of its own if there is not enough space
 * left. The first line is centered in a band as tall as a regular
 * text field, so the field lines up with its neighbours.
 */
@Composable
private fun TagsFlowLayout(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = content,
    ) { measurables, constraints ->
        val spacing = ChipSpacing.roundToPx()
        val inputMinWidth = InputMinWidth.roundToPx()
        val lineMinHeight = LineMinHeight.roundToPx()
        val width = constraints.maxWidth

        class Item(
            val placeable: Placeable,
            val x: Int,
            val line: Int,
        )

        val items = ArrayList<Item>(measurables.size)
        val lineHeights = mutableListOf(0)
        var x = 0
        measurables.forEach { measurable ->
            val placeable = if (measurable.layoutId == INPUT_LAYOUT_ID) {
                val remaining = width - x
                if (x > 0 && remaining < inputMinWidth) {
                    lineHeights += 0
                    x = 0
                }
                measurable.measure(Constraints.fixedWidth(width - x))
            } else {
                val placeable = measurable.measure(Constraints(maxWidth = width))
                if (x > 0 && x + placeable.width > width) {
                    lineHeights += 0
                    x = 0
                }
                placeable
            }
            val line = lineHeights.lastIndex
            items += Item(placeable, x, line)
            lineHeights[line] = maxOf(lineHeights[line], placeable.height)
            x += placeable.width + spacing
        }

        val contentHeight = lineHeights.sum() + spacing * lineHeights.lastIndex
        val padding = ((lineMinHeight - lineHeights.first()) / 2).coerceAtLeast(0)
        val height = maxOf(lineMinHeight, contentHeight + padding * 2)
        val lineTops = lineHeights.runningFold(padding) { top, lineHeight ->
            top + lineHeight + spacing
        }
        layout(width, height) {
            items.forEach { item ->
                val lineHeight = lineHeights[item.line]
                val y = lineTops[item.line] + (lineHeight - item.placeable.height) / 2
                item.placeable.placeRelative(item.x, y)
            }
        }
    }
}

/**
 * Shows the rest of the suggested tag after the caret.
 * The caret can not move into the [ghost] text.
 */
private data class GhostTextTransformation(
    val ghost: String,
    val color: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val out = buildAnnotatedString {
            append(text)
            withStyle(SpanStyle(color = color)) {
                append(ghost)
            }
        }
        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = offset

            override fun transformedToOriginal(offset: Int) = offset.coerceAtMost(text.length)
        }
        return TransformedText(out, offsetMapping)
    }
}
