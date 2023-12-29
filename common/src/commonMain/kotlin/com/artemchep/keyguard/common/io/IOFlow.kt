package com.artemchep.keyguard.common.io

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

fun <T> Flow<T>.toIO(): IO<T> = ioEffect { first() }

fun <T> Flow<T>.attempt(): Flow<Either<Throwable, T>> = this
    .map<T, Either<Throwable, T>> { it.right() }
    .catch {
        val value = it.left()
        emit(value)
    }
