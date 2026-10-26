package com.artemchep.keyguard.feature.home.settings.shortcuts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.GlobalShortcutAction
import com.artemchep.keyguard.common.model.GlobalShortcutBinding
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.shortcut.GlobalShortcutError
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.theme.Dimens
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

@Composable
fun ShortcutRecorderScreen(action: GlobalShortcutAction) {
    val state = produceShortcutRecorderState(action)
    if (state is Loadable.Ok) {
        ShortcutRecorderContent(state.value)
    }
}

private class RecorderDraft {
    val recorder = ShortcutRecorder(CurrentPlatform)
    val focusRequester = FocusRequester()
    var focused by mutableStateOf(false)
    var candidate by mutableStateOf<GlobalShortcutBinding?>(null)
    var invalid by mutableStateOf(false)

    /** Consumes every key while the recorder has focus, except Tab traversal. */
    fun onKeyEvent(
        event: KeyEvent,
        state: ShortcutRecorderState,
    ): Boolean {
        if (event.type != KeyEventType.KeyDown) return true
        val result =
            recorder.handle(
                key = event.key,
                ctrl = event.isCtrlPressed,
                shift = event.isShiftPressed,
                alt = event.isAltPressed,
                meta = event.isMetaPressed,
                altGraph = event.isAltGraphPressed,
            )
        when (result) {
            ShortcutRecorder.Result.Ignored,
            ShortcutRecorder.Result.Traverse,
            -> {
                Unit
            }

            ShortcutRecorder.Result.Cancel -> {
                state.onDismiss()
            }

            ShortcutRecorder.Result.Invalid -> {
                candidate = null
                invalid = true
            }

            is ShortcutRecorder.Result.Captured -> {
                candidate = result.binding
                invalid = false
                state.onClearError()
            }
        }
        // Tab moves focus to the dialog buttons.
        return result != ShortcutRecorder.Result.Traverse
    }
}

/** Global shortcuts would swallow their own keys, so they pause while the recorder has focus. */
@Composable
private fun RecordingEffect(
    state: ShortcutRecorderState,
    recording: Boolean,
) {
    val startRecording by rememberUpdatedState(state.startRecording)
    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        val lease = withContext(NonCancellable) { startRecording() }
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { lease.release() }
        }
    }
}

@Composable
internal fun ShortcutRecorderContent(state: ShortcutRecorderState) {
    val draft = remember { RecorderDraft() }
    RecordingEffect(state, recording = draft.focused && LocalWindowInfo.current.isWindowFocused)
    val error = GlobalShortcutError.Invalid.takeIf { draft.invalid } ?: state.error
    val title = shortcutActionTitle(state.action)
    Dialog(
        icon = { Icon(imageVector = Icons.Outlined.KeyboardAlt, contentDescription = null) },
        title = { Text(title) },
        content = {
            Column(
                modifier = Modifier.padding(horizontal = Dimens.horizontalPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RecorderBinding(draft, state, title)
                error?.let { Text(shortcutErrorText(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        actions = {
            TextButton(onClick = state.onDismiss, enabled = !state.busy) {
                Text(stringResource(Res.string.cancel))
            }
            TextButton(
                enabled = draft.candidate != null && !state.busy && !error.blocksSaving(),
                onClick = { draft.candidate?.let(state.onSave) },
            ) { Text(stringResource(Res.string.save)) }
        },
    )
}

private fun GlobalShortcutError?.blocksSaving(): Boolean =
    when (this) {
        GlobalShortcutError.Invalid,
        is GlobalShortcutError.Duplicate,
        GlobalShortcutError.UnsupportedPlatform,
        GlobalShortcutError.UnsupportedSession,
        -> true

        GlobalShortcutError.Unavailable,
        GlobalShortcutError.Registration,
        GlobalShortcutError.Persistence,
        GlobalShortcutError.PersistenceRestoration,
        null,
        -> false
    }

@Composable
private fun RecorderBinding(
    draft: RecorderDraft,
    state: ShortcutRecorderState,
    title: String,
) {
    val bindingLabel = stringResource(Res.string.keyboard_shortcuts_binding_label, title)
    val candidate = draft.candidate
    val candidateText = remember(candidate) { candidate?.displayText() }
    val recorderText = candidateText ?: stringResource(Res.string.keyboard_shortcuts_recorder_hint)
    LaunchedEffect(draft.focusRequester) {
        draft.focusRequester.requestFocus()
    }
    OutlinedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(draft.focusRequester)
                .onFocusChanged { draft.focused = it.isFocused }
                .onPreviewKeyEvent { draft.onKeyEvent(it, state) }
                .clickable(enabled = !state.busy) {
                    draft.focusRequester.requestFocus()
                }.semantics {
                    contentDescription = bindingLabel
                    stateDescription = recorderText
                },
    ) {
        Text(recorderText, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
    }
}
