package com.artemchep.keyguard.ui.tags

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.input.TextFieldValue
import com.artemchep.keyguard.feature.tags.TagSuggestion
import com.artemchep.keyguard.feature.tags.TagsFieldModel
import com.artemchep.keyguard.feature.tags.splitTags
import com.artemchep.keyguard.feature.tags.tagCompletion

/**
 * Holds the transient state of a [TagsField] and handles
 * its input; the tags themselves live in the model.
 */
@Stable
internal class TagsFieldController(
    private val valueState: State<TagsFieldModel>,
    val buffer: MutableState<TextFieldValue>,
    private val haptic: HapticFeedback,
) {
    private class Highlight(
        val suggestions: List<TagSuggestion>,
        val index: Int,
    )

    val value get() = valueState.value

    val editable get() = value.onCommit != null

    val focusRequester = FocusRequester()

    /** Whether the input has the focus. */
    var hasFocus by mutableStateOf(false)
        private set

    /** Whether the input or one of the chips has the focus. */
    var fieldHasFocus by mutableStateOf(false)
        private set

    /** The last chip, armed for removal by a Backspace in the empty input. */
    var armed by mutableStateOf(false)

    /** Escape hides the suggestions until the draft changes. */
    var dismissed by mutableStateOf(false)

    var shake by mutableStateOf<TagShake?>(null)
        private set

    private var highlight by mutableStateOf<Highlight?>(null)

    /** Where the picked suggestions were, so their chips can fly from there. */
    private val flyOrigins = mutableMapOf<String, Offset>()

    /**
     * The index of the suggestion picked with the arrow keys, -1 if
     * none. It is forgotten when the suggestions change.
     */
    var highlighted: Int
        get() = highlight
            ?.takeIf { it.suggestions == value.suggestions }
            ?.index
            ?: -1
        set(index) {
            highlight = Highlight(value.suggestions, index)
                .takeIf { index >= 0 }
        }

    val panelVisible: Boolean
        get() = editable &&
            fieldHasFocus &&
            !dismissed &&
            value.suggestions.isNotEmpty()

    /** The rest of the suggested tag, shown after the caret. */
    val completion: String?
        get() {
            val buffer = buffer.value
            val caretAtEnd = buffer.selection.collapsed &&
                buffer.selection.end == buffer.text.length
            if (!hasFocus || !caretAtEnd) {
                return null
            }
            val candidates = value.suggestions.getOrNull(highlighted)
                ?.let(::listOf)
                ?: value.suggestions
            return tagCompletion(buffer.text, candidates)
        }

    fun takeFlyOrigin(tag: String) = flyOrigins.remove(tag.lowercase())

    fun commit(text: String, feedback: Boolean = true) {
        val input = splitTags(text)
        if (input.isEmpty()) {
            return
        }
        val tags = value.tags
        val existing = input
            .mapNotNull { tag -> tags.firstOrNull { it.equals(tag, ignoreCase = true) } }
        if (existing.size == input.size) {
            // Every tag is already there: point at it
            // instead of adding a duplicate.
            shake = TagShake(existing.first())
            value.draft.onSetText?.invoke("")
            if (feedback) haptic.performHapticFeedback(HapticFeedbackType.Reject)
            return
        }

        value.onCommit?.invoke(text)
        if (feedback) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    fun pick(name: String, origin: Offset?) {
        if (origin != null) {
            flyOrigins[name.lowercase()] = origin
        }
        commit(name)
    }

    fun edit(tag: String) {
        value.onEdit?.invoke(tag)
        focusRequester.requestFocus()
    }

    fun remove(tag: String) {
        value.onRemove?.invoke(tag)
        // The focus may be on the chip that is
        // going away; give it back to the input.
        if (fieldHasFocus && !hasFocus) {
            focusRequester.requestFocus()
        }
    }

    /** Picks the highlighted suggestion, or adds the typed text. */
    fun submit() {
        val suggestion = value.suggestions.getOrNull(highlighted)
        if (suggestion != null) {
            pick(suggestion.name, origin = null)
        } else {
            commit(buffer.value.text)
        }
    }

    fun arm() {
        armed = true
        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    fun onValueChange(next: TextFieldValue) {
        if (next.text != buffer.value.text) {
            armed = false
            dismissed = false
        }
        buffer.value = next
        value.draft.onChange?.invoke(next.text)
    }

    fun onFocusChanged(focused: Boolean) {
        if (hasFocus && !focused) {
            armed = false
            highlight = null
        }
        hasFocus = focused
    }

    fun onFieldFocusChanged(focused: Boolean) {
        if (fieldHasFocus && !focused) {
            dismissed = false
            // Leaving the field keeps what was typed.
            commit(buffer.value.text, feedback = false)
        }
        fieldHasFocus = focused
    }
}
