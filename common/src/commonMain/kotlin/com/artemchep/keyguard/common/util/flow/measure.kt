package com.artemchep.keyguard.common.util.flow

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlin.time.Duration
import kotlin.time.TimeSource

inline fun <T> Flow<T>.measureTimeTillFirstEvent(
    crossinline predicate: (T) -> Boolean = { true },
    crossinline block: suspend (Duration, T) -> Unit,
): Flow<T> = flow {
    var hasCompleted = false

    val timeMark = TimeSource.Monotonic.markNow()
    val newFlow = this@measureTimeTillFirstEvent
        .onEach { model ->
            if (predicate(model) && !hasCompleted) {
                hasCompleted = true

                val dt = timeMark.elapsedNow()
                block(dt, model)
            }
        }
    emitAll(newFlow)
}
