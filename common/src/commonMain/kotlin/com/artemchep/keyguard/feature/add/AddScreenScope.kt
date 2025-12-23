package com.artemchep.keyguard.feature.add

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.artemchep.keyguard.ui.focus.FocusRequester2
import kotlinx.coroutines.delay

class AddScreenScope(
    initialFocusRequested: Boolean = false,
) {
    private val itemsStateSink = mutableStateOf<List<AddStateItem>>(emptyList())

    val itemsState: State<List<AddStateItem>> get() = itemsStateSink

    val initialFocusRequestedState = mutableStateOf(initialFocusRequested)

    @Composable
    fun initialFocusRequesterEffect(): FocusRequester2 {
        val focusRequester = remember { FocusRequester2() }
        // Auto focus the text field
        // on launch.
        LaunchedEffect(focusRequester) {
            var initialFocusRequested by initialFocusRequestedState
            if (!initialFocusRequested) {
                delay(100L)
                focusRequester.requestFocus()
                // do not request it the second time
                initialFocusRequested = true
            }
        }
        return focusRequester
    }

    fun updateItems(items: List<AddStateItem>) {
        itemsStateSink.value = items
    }
}