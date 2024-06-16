package com.artemchep.keyguard.common.util

import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.postDebug
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.datetime.Clock

inline fun <T> Flow<T>.withLogTimeOfFirstEvent(
    logRepository: LogRepository,
    tag: String,
    crossinline predicate: (T) -> Boolean = { true },
): Flow<T> = flow {
    var hasCompleted = false
    val startTime = Clock.System.now()
    emitAll(
        this@withLogTimeOfFirstEvent.onEach { model ->
            if (predicate(model) && !hasCompleted) {
                hasCompleted = true
                val now = Clock.System.now()
                val dt = now - startTime
                logRepository.postDebug(tag) {
                    val suffix = if (model is Collection<*>) {
                        val size = model.size
                        "Loaded $size models."
                    } else {
                        ""
                    }
                    "It took ${dt}. to load first portion of data. $suffix"
                }
            }
        },
    )
}
