package com.artemchep.keyguard.common.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import kotlin.time.DurationUnit
import kotlin.time.toDuration

fun flowOfTime(
    unit: DurationUnit = DurationUnit.SECONDS,
    duration: Long = 1L,
) = flow {
    val delayMs = duration.toDuration(unit).inWholeMilliseconds
    while (true) {
        val time = Clock.System.now()
        emit(time)
        delay(delayMs)
    }
}
