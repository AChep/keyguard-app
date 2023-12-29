package com.artemchep.keyguard.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.Flow

@Composable
fun <T> CollectedEffect(
    flow: Flow<T>,
    action: suspend (T) -> Unit,
) {
    LaunchedEffect(flow) {
        flow.collect {
            action(it)
        }
    }
}
