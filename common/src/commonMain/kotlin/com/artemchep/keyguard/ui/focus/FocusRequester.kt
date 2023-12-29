package com.artemchep.keyguard.ui.focus

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.ui.CollectedEffect
import kotlinx.coroutines.flow.Flow

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

    val keyboard = LocalSoftwareKeyboardController.current
    CollectedEffect(focusRequester2.onRequestFocusFlow) { event ->
        val isFocused = focusedStateState.value?.isFocused == true
        if (isFocused && event.showKeyboard) {
            // We can not re-focus it again, see:
            // https://stackoverflow.com/questions/74391260/jetpack-compose-requestfocus-works-only-once
            keyboard?.show()
            return@CollectedEffect
        }

        focusRequester.requestFocus()
    }

    Modifier
        .onFocusChanged { state ->
            focusedStateState.value = state
        }
        .focusRequester(focusRequester)
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
