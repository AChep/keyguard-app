package com.artemchep.keyguard.common.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime
import kotlin.time.toTimeUnit

fun flowOfTime(
    unit: DurationUnit = DurationUnit.SECONDS,
    duration: Long = 1L,
) = flow {
    val delayMs = unit.toTimeUnit().toMillis(duration)
    while (true) {
        val time = Clock.System.now()
        emit(time)
        delay(delayMs)
    }
}
