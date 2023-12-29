package com.artemchep.keyguard.common.io

import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue

inline fun <T> IO<T>.measure(crossinline block: suspend (Duration, T) -> Unit): IO<T> = {
    val r = measureTimedValue {
        invoke()
    }
    block(r.duration, r.value)
    r.value
}
