package com.artemchep.keyguard.feature.dialog

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import com.artemchep.keyguard.feature.navigation.keyboard.hasShortcutModifier
import com.artemchep.keyguard.ui.focus.scrollToItemInContext
import kotlinx.coroutines.launch

/** The choice a dialog opens on: the selected enabled one, else the first enabled one. */
internal fun <T> List<T>.initialChoice(
    selected: (T) -> Boolean,
    enabled: (T) -> Boolean,
): T? = filter(enabled).let { choices -> choices.firstOrNull(selected) ?: choices.firstOrNull() }

private fun KeyEvent.isTab() = type == KeyEventType.KeyDown && key == Key.Tab && !hasShortcutModifier

// The composed modifier below is keyed, so equal arguments give equal
// modifiers and the composables that receive them can still skip.

/** Multiline dialog editors keep Enter for line breaks and Tab for focus traversal. */
fun Modifier.dialogEditorTabTraversal(singleLine: Boolean = false): Modifier = if (singleLine) this else composed(
    fullyQualifiedName = "com.artemchep.keyguard.feature.dialog.dialogEditorTabTraversal",
    key1 = Unit,
) {
    val focus = LocalFocusManager.current
    Modifier.onPreviewKeyEvent { event ->
        if (event.isTab()) {
            focus.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
            true
        } else false
    }
}

/** Enter a neighboring group with its normal entry focus, even when it contains lazy rows. */
internal fun Modifier.dialogTabTargets(previous: FocusRequester, next: FocusRequester): Modifier =
    onPreviewKeyEvent { event ->
        if (event.isTab()) {
            (if (event.isShiftPressed) previous else next).requestFocus()
        } else false
    }

/**
 * Focuses the item at [index] once per [key], after [scrollToItemInContext]
 * with the [contextIndex]. A null [index] focuses nothing. Attach the returned
 * requester to that item.
 */
@Composable
internal fun LazyListState.rememberInitialItemFocus(
    index: Int?,
    contextIndex: Int,
    key: Any? = Unit,
): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(key) {
        if (index == null) return@LaunchedEffect
        scrollToItemInContext(index, contextIndex)
        requester.requestFocus()
    }
    return requester
}

/** IME Next moves between form items, skipping controls such as Clear inside an editor. */
class DialogFormKeyboard internal constructor(
    private val keys: List<String>,
    private val requesters: Map<String, FocusRequester>,
    private val actions: Map<String, KeyboardActions>,
) {
    fun target(key: String): Modifier = requesters[key]?.let { Modifier.focusRequester(it) } ?: Modifier

    fun imeAction(key: String, singleLine: Boolean): ImeAction = when {
        !singleLine -> ImeAction.Default
        key == keys.lastOrNull() -> ImeAction.Done
        else -> ImeAction.Next
    }

    /** Next focuses the following item, Done calls the form's `onDone`. */
    fun actions(key: String): KeyboardActions = actions[key] ?: KeyboardActions.Default
}

@Composable
fun rememberDialogFormKeyboard(
    keys: List<String>,
    initialEditor: String?,
    onDone: (() -> Unit)?,
    showItem: suspend (String) -> Unit = {},
): DialogFormKeyboard {
    val requesters = remember(keys) { keys.associateWith { FocusRequester() } }
    val currentOnDone by rememberUpdatedState(onDone)
    val currentShowItem by rememberUpdatedState(showItem)
    val scope = rememberCoroutineScope()
    // Focus the initial editor once. It may go away and come back while the
    // form is busy; that must not pull focus from where the user moved it.
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(initialEditor, requesters) {
        if (initialFocusDone || initialEditor == null) return@LaunchedEffect
        val requester = requesters[initialEditor] ?: return@LaunchedEffect
        requester.requestFocus()
        initialFocusDone = true
    }
    return remember(requesters, scope) {
        val actions = keys.withIndex().associate { (index, key) ->
            val nextKey = keys.getOrNull(index + 1)
            key to KeyboardActions(
                onNext = {
                    if (nextKey != null) {
                        scope.launch {
                            currentShowItem(nextKey)
                            requesters.getValue(nextKey).requestFocus()
                        }
                    }
                },
                onDone = { currentOnDone?.invoke() },
            )
        }
        DialogFormKeyboard(keys, requesters, actions)
    }
}
