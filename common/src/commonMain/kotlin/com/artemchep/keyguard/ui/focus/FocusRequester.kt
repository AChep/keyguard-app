package com.artemchep.keyguard.ui.focus

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.navigation.LocalNavigationNodeFinishing
import com.artemchep.keyguard.ui.CollectedEffect
import kotlinx.coroutines.flow.Flow

/**
 * Links the [focusRequester2] to the element. Content that is not live,
 * for example a screen that a Back gesture previews, takes no focus: it
 * holds the latest request and replays it once the content is live.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.focusRequester2(
    focusRequester2: FocusRequester2,
) = composed {
    val focusedStateState = remember {
        mutableStateOf<FocusState?>(null)
    }
    val focusRequester = remember {
        FocusRequester()
    }
    val pending = remember {
        PendingFocusEvent()
    }

    val keyboardState = rememberUpdatedState(LocalSoftwareKeyboardController.current)
    val finishing = LocalNavigationNodeFinishing.current
    val finishingState = rememberUpdatedState(finishing)

    fun handle(event: FocusEvent) {
        val isFocused = focusedStateState.value?.isFocused == true
        if (isFocused && event.showKeyboard) {
            // We can not re-focus it again, see:
            // https://stackoverflow.com/questions/74391260/jetpack-compose-requestfocus-works-only-once
            keyboardState.value?.show()
            return
        }

        focusRequester.requestFocus()
    }

    CollectedEffect(focusRequester2.onRequestFocusFlow) { event ->
        if (finishingState.value) {
            pending.event = event
            return@CollectedEffect
        }
        handle(event)
    }
    LaunchedEffect(finishing) {
        if (finishing) {
            return@LaunchedEffect
        }
        val event = pending.event
            ?: return@LaunchedEffect
        pending.event = null
        handle(event)
    }

    Modifier
        .onFocusChanged { state ->
            focusedStateState.value = state
        }
        .focusRequester(focusRequester)
}

/** The latest request held while the content is not live. Effects only. */
private class PendingFocusEvent {
    var event: FocusEvent? = null
}

class FocusRequester2 {
    private val onRequestFocusSink = EventFlow<FocusEvent>()

    val onRequestFocusFlow: Flow<FocusEvent> get() = onRequestFocusSink

    /**
     * Requests a focus on associated
     * compose node.
     */
    fun requestFocus(
        showKeyboard: Boolean = true,
    ) = onRequestFocusSink.emit(FocusEvent(showKeyboard))
}

class FocusEvent(
    val showKeyboard: Boolean,
)
