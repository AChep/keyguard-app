package com.artemchep.keyguard.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.artemchep.keyguard.common.util.flowOfTime
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The time left until [until], as `42` under a minute and `4:59` from a
 * minute up; `null` if there is no deadline or it never comes.
 */
@Composable
fun rememberCountdownText(until: Instant?): String? {
    val state = remember {
        mutableStateOf<String?>(null)
    }

    LaunchedEffect(until) {
        if (until == null) {
            state.value = null
            return@LaunchedEffect
        }

        flowOfTime()
            .onEach { now ->
                val dt = until - now
                state.value = if (dt.isInfinite()) {
                    null
                } else {
                    formatCountdown(dt)
                }
            }
            .collect()
    }

    return state.value
}

internal fun formatCountdown(
    duration: Duration,
): String = duration
    .coerceAtLeast(Duration.ZERO)
    .toComponents { minutes, seconds, _ ->
        if (minutes == 0L) {
            seconds.toString()
        } else {
            "$minutes:${seconds.toString().padStart(2, '0')}"
        }
    }
