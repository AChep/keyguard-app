package com.artemchep.keyguard.feature.crashlytics

import arrow.core.Either
import arrow.core.getOrElse
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.handleErrorTap
import com.artemchep.keyguard.platform.recordException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach

fun <T> Flow<T>.crashlyticsAttempt(
    transform: (Throwable) -> Throwable? = { it },
) = this
    .attempt()
    .onEach { result ->
        if (result is Either.Left) {
            val newException = transform(result.value)
            if (newException != null) {
                recordException(newException)
            }
        }
    }

fun <T> Flow<T>.crashlyticsMap(
    transform: (Throwable) -> Throwable? = { it },
    orElse: (Throwable) -> T,
) = crashlyticsFlatMapConcat(
    transform = transform,
    orElse = {
        val fallbackValue = orElse(it)
        flowOf(fallbackValue)
    },
)

fun <T> Flow<T>.crashlyticsFlatMapConcat(
    transform: (Throwable) -> Throwable? = { it },
    orElse: Flow<T>.(Throwable) -> Flow<T>,
): Flow<T> = this
    .attempt()
    .flatMapConcat { result ->
        result
            .map { flowOf(it) }
            .getOrElse { e ->
                val newException = transform(e)
                if (newException != null) {
                    recordException(newException)
                }
                // Fall back to the default value.
                orElse(e)
            }
    }

fun <T> IO<T>.crashlyticsTap(
    transform: (Throwable) -> Throwable? = { it },
) = this
    .handleErrorTap { e ->
        val newException = transform(e)
        if (newException != null) {
            recordException(newException)
        }
    }
