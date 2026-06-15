package com.artemchep.keyguard.common.service.quicksearch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class QuickSearchWindowState(
    val visible: Boolean = false,
    val requestRevision: Int = 0,
)

class QuickSearchWindowManager {
    private val mutableStateFlow = MutableStateFlow(QuickSearchWindowState())

    val stateFlow: StateFlow<QuickSearchWindowState> = mutableStateFlow.asStateFlow()

    fun requestOpen() {
        mutableStateFlow.update { state ->
            state.copy(
                visible = true,
                requestRevision = state.requestRevision + 1,
            )
        }
    }

    fun dismiss() {
        mutableStateFlow.update { state ->
            if (!state.visible) {
                state
            } else {
                state.copy(visible = false)
            }
        }
    }
}
