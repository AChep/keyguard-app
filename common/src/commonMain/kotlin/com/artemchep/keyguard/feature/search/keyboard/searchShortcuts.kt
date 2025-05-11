package com.artemchep.keyguard.feature.search.keyboard

import androidx.compose.ui.input.key.Key
import com.artemchep.keyguard.feature.navigation.keyboard.KeyShortcut
import com.artemchep.keyguard.feature.navigation.keyboard.interceptKeyEvents
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.search.search.SearchQueryHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

fun RememberStateFlowScope.searchQueryShortcuts(
    searchQueryHandle: SearchQueryHandle,
) = searchQueryShortcuts(
    queryFlow = searchQueryHandle.querySink,
    clearField = {
        searchQueryHandle.queryState.value = ""
    },
    focusField = {
        searchQueryHandle.focusSink.emit(Unit)
    },
)

fun RememberStateFlowScope.searchQueryShortcuts(
    queryFlow: Flow<String>,
    clearField: () -> Unit,
    focusField: () -> Unit,
) {
    interceptKeyEvents(
        // Ctrl+Alt+F: Focus search field
        KeyShortcut(
            key = Key.F,
            isCtrlPressed = true,
            isAltPressed = true,
        ) to flowOf(true)
            .map { enabled ->
                if (enabled) {
                    // lambda
                    {
                        clearField()
                        focusField()
                    }
                } else {
                    null
                }
            },
    )
}
