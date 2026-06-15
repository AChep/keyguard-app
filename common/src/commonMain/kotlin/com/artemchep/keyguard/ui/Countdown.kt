package com.artemchep.keyguard.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.artemchep.keyguard.common.util.flowOfTime
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlin.time.Instant

@Composable
fun rememberCountdownSeconds(until: Instant?): Int? {
    val state = remember {
        mutableStateOf<Int?>(null)
    }

    LaunchedEffect(until) {
        if (until == null) {
            state.value = null
            return@LaunchedEffect
        }

        flowOfTime()
            .onEach { now ->
                val dt = (until - now).inWholeSeconds
                    .coerceAtLeast(0)
                state.value = dt.toInt()
            }
            .collect()
    }

    return state.value
}
