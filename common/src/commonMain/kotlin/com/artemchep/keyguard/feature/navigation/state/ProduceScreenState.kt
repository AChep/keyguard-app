package com.artemchep.keyguard.feature.navigation.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.Flow

@Composable
fun <T> produceScreenState(
    key: String,
    args: Array<Any?> = emptyArray(),
    rargs: Array<Any?> = emptyArray(),
    initial: T,
    init: suspend RememberStateFlowScope.() -> Flow<T>,
): T {
    val stateFlow by rememberScreenState(
        key = key,
        args = args,
        rargs = rargs,
        initial = initial,
        init = init,
    )
    return stateFlow
}

@Composable
fun <T> rememberScreenState(
    key: String,
    args: Array<Any?> = emptyArray(),
    rargs: Array<Any?> = emptyArray(),
    initial: T,
    init: suspend RememberStateFlowScope.() -> Flow<T>,
): State<T> = rememberScreenStateFlow(
    key = key,
    args = args,
    rargs = rargs,
    initial = initial,
    init = init,
).collectAsState()
