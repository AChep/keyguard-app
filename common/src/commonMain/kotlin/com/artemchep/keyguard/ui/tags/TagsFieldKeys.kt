package com.artemchep.keyguard.ui.tags

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Handles a key pressed in the input of a [TagsField];
 * returns `true` if the key was consumed.
 */
internal fun TagsFieldController.onKeyEvent(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) {
        return false
    }
    if (event.key != Key.Backspace) {
        armed = false
    }
    return when (event.key) {
        Key.Enter, Key.NumPadEnter -> submitOnPlainEnter(event)
        // Tab never traps the focus: with nothing
        // to accept it moves on.
        Key.Tab -> !event.isShiftPressed && acceptSuggestion()
        Key.DirectionRight -> fillCompletion()
        Key.DirectionDown -> moveHighlight(1)
        Key.DirectionUp -> moveHighlight(-1)
        Key.Escape -> dismiss()
        Key.Backspace -> removeLastTag()
        else -> false
    }
}

/** Leaves Enter with modifiers to the app's shortcuts. */
private fun TagsFieldController.submitOnPlainEnter(event: KeyEvent): Boolean {
    val plain = !event.isCtrlPressed &&
        !event.isMetaPressed &&
        !event.isAltPressed
    if (plain) {
        submit()
    }
    return plain
}

/** Adds the highlighted suggestion or the completed tag. */
private fun TagsFieldController.acceptSuggestion(): Boolean {
    val suggestion = value.suggestions.getOrNull(highlighted)
    val completion = completion
    when {
        suggestion != null -> pick(suggestion.name, origin = null)
        completion != null -> commit(buffer.value.text + completion)
        else -> return false
    }
    return true
}

/** Types the rest of the suggested tag. */
private fun TagsFieldController.fillCompletion(): Boolean {
    val completion = completion
        ?: return false
    val text = buffer.value.text + completion
    onValueChange(
        TextFieldValue(
            text = text,
            selection = TextRange(text.length),
        ),
    )
    return true
}

private fun TagsFieldController.moveHighlight(delta: Int): Boolean {
    if (!panelVisible) {
        return false
    }
    val size = value.suggestions.size
    val index = highlighted
    highlighted = when {
        index >= 0 -> (index + delta + size) % size
        delta > 0 -> 0
        else -> size - 1
    }
    return true
}

/** Clears the highlight, then hides the suggestions. */
private fun TagsFieldController.dismiss(): Boolean = when {
    highlighted >= 0 -> {
        highlighted = -1
        true
    }

    panelVisible -> {
        dismissed = true
        true
    }

    else -> false
}

/** The first Backspace arms the last chip, the second removes it. */
private fun TagsFieldController.removeLastTag(): Boolean {
    val lastTag = value.tags.lastOrNull()
    if (buffer.value.text.isNotEmpty() || lastTag == null) {
        return false
    }
    if (armed) {
        armed = false
        remove(lastTag)
    } else {
        arm()
    }
    return true
}
