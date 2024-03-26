package com.artemchep.keyguard.feature.add

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import com.artemchep.keyguard.ui.focus.FocusRequester2

class AddScreenScope(
    initialFocusRequested: Boolean = false,
) {
    val initialFocusRequestedState = mutableStateOf(initialFocusRequested)

    @Composable
    fun initialFocusRequesterEffect(): FocusRequester2 {
        val focusRequester = remember { FocusRequester2() }
        // Auto focus the text field
        // on launch.
        LaunchedEffect(focusRequester) {
            var initialFocusRequested by initialFocusRequestedState
            if (!initialFocusRequested) {
                focusRequester.requestFocus()
                // do not request it the second time
                initialFocusRequested = true
            }
        }
        return focusRequester
    }
}