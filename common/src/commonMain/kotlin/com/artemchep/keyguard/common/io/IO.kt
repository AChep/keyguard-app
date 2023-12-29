package com.artemchep.keyguard.common.io

import arrow.core.partially1

typealias IO<T> = suspend () -> T

fun <A, T, R> (suspend (A) -> T).lift(block: (IO<T>) -> IO<R>): suspend (A) -> R = {
    partially1(it)
        .let(block)
        .bind()
}
