package com.artemchep.keyguard.common.io

import arrow.core.Either
import arrow.core.getOrElse

fun <T> IO<Either<Throwable, T>>.flattenMap(): IO<T> =
    effectMap {
        it.getOrElse { throw it }
    }

fun <T> IO<IO<T>>.flatten(): IO<T> =
    flatMap {
        it
    }
