package com.artemchep.keyguard.platform.lifecycle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual val LocalLifecycleStateFlow: StateFlow<LeLifecycleState>
    @Composable
    get() {
        val sink = remember {
            MutableStateFlow(LeLifecycleState.STARTED)
        }
        return sink
    }
