package com.artemchep.keyguard.common.util

import arrow.core.Either
import arrow.core.left
import arrow.core.right

suspend fun <T> Either.Companion.catch(
    f: suspend () -> T,
): Either<Throwable, T> = arrow.core.raise.catch({ f().right() }) { it.left() }
