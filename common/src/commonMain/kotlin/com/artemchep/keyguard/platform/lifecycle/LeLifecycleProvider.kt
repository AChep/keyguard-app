package com.artemchep.keyguard.platform.lifecycle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

val LocalLifecycleStateFlow: StateFlow<LeLifecycleState>
    @Composable
    get() {
        val lifecycleOwner = LocalLifecycleOwner.current
        val sink = remember(lifecycleOwner) {
            val initialState = lifecycleOwner.lifecycle.currentState
            MutableStateFlow(initialState.toCommon())
        }
        DisposableEffect(lifecycleOwner, sink) {
            val observer = LifecycleEventObserver { _, event ->
                val newState = event.targetState
                sink.value = newState.toCommon()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
        return sink
    }

fun Lifecycle.State.toCommon() = when (this) {
    Lifecycle.State.DESTROYED -> LeLifecycleState.DESTROYED
    Lifecycle.State.INITIALIZED -> LeLifecycleState.INITIALIZED
    Lifecycle.State.CREATED -> LeLifecycleState.CREATED
    Lifecycle.State.STARTED -> LeLifecycleState.STARTED
    Lifecycle.State.RESUMED -> LeLifecycleState.RESUMED
}
