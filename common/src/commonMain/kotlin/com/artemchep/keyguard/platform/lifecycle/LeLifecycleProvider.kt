package com.artemchep.keyguard.platform.lifecycle

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

@get:Composable
expect val LocalLifecycleStateFlow: StateFlow<LeLifecycleState>
