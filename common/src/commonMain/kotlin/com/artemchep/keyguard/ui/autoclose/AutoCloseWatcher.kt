package com.artemchep.keyguard.ui.autoclose

import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

fun <T : Any> RememberStateFlowScope.launchAutoPopSelfHandler(
    contentFlow: Flow<T?>,
) {
    // Auto-close the screen if a model disappears.
    // This happens if a user deletes the
    // cipher, for example.
    contentFlow
        // We drop the first event, because we don't want to never let
        // the user open the screen if the model doesn't exist, we want to
        // close it if the model existed before and a user has seen it.
        .drop(1)
        .filter { it == null }
        // Pop the screen.
        .onEach { navigatePopSelf() }
        .launchIn(screenScope)
}
