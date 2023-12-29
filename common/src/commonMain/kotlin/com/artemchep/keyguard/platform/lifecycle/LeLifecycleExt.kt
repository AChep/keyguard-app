package com.artemchep.keyguard.platform.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

fun <T> Flow<T>.flowWithLifecycle(
    lifecycleStateFlow: Flow<LeLifecycleState>,
    minActiveState: LeLifecycleState = LeLifecycleState.STARTED,
): Flow<T> = lifecycleStateFlow
    .map { lifecycleState ->
        lifecycleState >= minActiveState
    }
    .distinctUntilChanged()
    // Collect the underlying flow when the lifecycle
    // is in an active state.
    .flatMapLatest { shouldBeActive ->
        if (shouldBeActive) {
            this
        } else {
            emptyFlow()
        }
    }

fun Flow<LeLifecycleState>.onState(
    minActiveState: LeLifecycleState = LeLifecycleState.STARTED,
    block: suspend CoroutineScope.() -> Unit,
) = run {
    val flow = flow<Unit> {
        coroutineScope(block)
    }
    flow
        .flowWithLifecycle(this, minActiveState = minActiveState)
}
