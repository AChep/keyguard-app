package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.home.vault.model.Visibility
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.VisibilityIcon
import com.artemchep.keyguard.ui.markdown.MarkdownText
import com.artemchep.keyguard.ui.theme.Dimens
import kotlin.time.Clock
import org.jetbrains.compose.resources.stringResource

@Composable
fun VaultViewNoteItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Note,
) {
    val visibilityConfig = item.visibility
    val visibilityState = rememberVisibilityState(
        visibilityConfig,
    )
    val revealed = visibilityState.value.value

    // The reveal row leaves once used, and its focus would drop to
    // the root. A reveal from the keyboard focuses the note instead.
    val revealFocus = remember { NoteRevealFocus() }
    val noteFocusRequester = remember { FocusRequester() }
    LaunchedEffect(revealed) {
        if (!revealed || !revealFocus.focusNote) return@LaunchedEffect
        revealFocus.focusNote = false
        // The note is composed by now. Once it is placed,
        // it can scroll into view as it takes focus.
        withFrameNanos { }
        noteFocusRequester.requestFocus()
    }
    Column(
        modifier = modifier,
    ) {
        if (!revealed) {
            val updatedVisibilityConfig by rememberUpdatedState(visibilityConfig)
            val inputModeManager = LocalInputModeManager.current
            FlatItemSimpleExpressive(
                leading = {
                    VisibilityIcon(
                        visible = visibilityState.value.value,
                    )
                },
                title = {
                    val text = if (visibilityState.value.value) {
                        stringResource(Res.string.hide_secure_note)
                    } else {
                        stringResource(Res.string.reveal_secure_note)
                    }
                    Text(
                        text = text,
                    )
                },
                onClick = {
                    // A mouse click focuses nothing.
                    val fromKeyboard = revealFocus.rowFocused &&
                        inputModeManager.inputMode == InputMode.Keyboard
                    updatedVisibilityConfig.transformUserEvent(!visibilityState.value.value) { newValue ->
                        revealFocus.focusNote = newValue && fromKeyboard
                        visibilityState.value = Visibility.Event(
                            value = newValue,
                            timestamp = Clock.System.now(),
                        )
                    }
                },
                rowModifier = Modifier
                    .onFocusChanged { state ->
                        revealFocus.rowFocused = state.isFocused
                    },
            )
        }
        ExpandedIfNotEmpty(
            Unit.takeIf { revealed },
            modifier = Modifier
                .fillMaxWidth(),
        ) {
            VaultViewNoteItemLayout(
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
                SelectionContainer(
                    modifier = Modifier
                        .focusRequester(noteFocusRequester),
                ) {
                    when (item.content) {
                        is VaultViewItem.Note.Content.Markdown -> {
                            MarkdownText(
                                markdown = item.content.document,
                            )
                        }
                        is VaultViewItem.Note.Content.Text -> {
                            Text(item.content.text)
                        }
                    }
                }
            }
        }
    }
}

/** The focus of the reveal row of a note. Event handlers and effects only. */
private class NoteRevealFocus {
    /** `true` while the reveal row has focus. */
    var rowFocused = false

    /** Set by a reveal from the keyboard: the note takes focus once it shows. */
    var focusNote = false
}

@Composable
fun VaultViewNoteItemLayout(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .padding(
                vertical = Dimens.contentPadding
                    .coerceAtLeast(16.dp),
                horizontal = Dimens.textHorizontalPadding,
            ),
    ) {
        content()
    }
}
