package com.artemchep.keyguard.common.model

import arrow.core.Either
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan

/**
 * @author Artem Chepurnyi
 */
sealed class RichResult<T> {
    companion object {
        operator fun <T> invoke(either: Either<Throwable, T>): RichResult<T> =
            either.fold(
                ifLeft = { Failure(it) },
                ifRight = { Success(it) },
            )
    }

    data class Failure<T>(
        val exception: Throwable,
    ) : RichResult<T>() {
        override fun equals(other: Any?): Boolean =
            other is Failure<*> && exception == other.exception

        override fun hashCode(): Int = exception.hashCode()
    }

    data class Loading<T>(
        /**
         * A progress or `null` if the progress is
         * not supported.
         */
        val progress: Float? = null,
        val lastTerminalState: RichResult<T>? = null,
    ) : RichResult<T>()

    data class Success<T>(
        val data: T,
    ) : RichResult<T>()
}

fun <T> RichResult<T>.getTerminalState() =
    when (this) {
        is RichResult.Loading<T> -> lastTerminalState
        else -> this
    }

fun <T> RichResult<T>.preferTerminalState() =
    when (this) {
        is RichResult.Loading<T> -> lastTerminalState ?: this
        else -> this
    }

fun <T> RichResult<T>.orNull() =
    when (this) {
        is RichResult.Success<T> -> data
        else -> null
    }

fun <T> RichResult<List<T>>.combine(other: RichResult<List<T>>): RichResult<List<T>> =
    when {
        this is RichResult.Success && other is RichResult.Success ->
            RichResult.Success(
                data = this.data + other.data,
            )

        this is RichResult.Loading || other is RichResult.Loading -> {
            val tts = this.getTerminalState()
            val ots = other.getTerminalState()

            val lastTerminalState =
                if (tts != null && ots != null) {
                    tts.combine(ots)
                } else {
                    tts ?: ots
                }
            RichResult.Loading(
                lastTerminalState = lastTerminalState,
            )
        }

        this is RichResult.Failure -> other
        else -> this
    }

fun <T, R> RichResult<T>.fold(
    ifFailure: (Throwable) -> R,
    ifLoading: (RichResult<T>?) -> R,
    ifSuccess: (T) -> R,
) = when (this) {
    is RichResult.Failure<T> -> ifFailure(exception)
    is RichResult.Loading<T> -> ifLoading(lastTerminalState)
    is RichResult.Success<T> -> ifSuccess(data)
}

fun <T, R> RichResult<T>.ap(ff: RichResult<(T) -> R>) =
    flatMap { t ->
        ff.map { it(t) }
    }

inline fun <T, R> RichResult<T>.map(transform: (T) -> R) =
    flatMap {
        RichResult.Success(transform(it))
    }

inline fun <T, R> RichResult<T>.flatMap(transform: (T) -> RichResult<R>) =
    when (this) {
        is RichResult.Failure<T> -> RichResult.Failure(exception)
        is RichResult.Loading<T> -> RichResult.Loading(progress)
        is RichResult.Success<T> -> transform(data)
    }

inline fun <T> RichResult<T>.handleError(transform: (Throwable) -> T) =
    handleErrorWith {
        RichResult.Success(transform(it))
    }

inline fun <T> RichResult<T>.handleErrorWith(transform: (Throwable) -> RichResult<T>) =
    when (this) {
        is RichResult.Failure<T> -> transform(exception)
        is RichResult.Loading<T> -> this
        is RichResult.Success<T> -> this
    }

inline fun <T, R> Flow<RichResult<T>>.richMap(crossinline block: suspend (T) -> R) = this
    .map { result ->
        result.map {
            block(it)
        }
    }

inline fun <T, R> Flow<RichResult<T>>.richFlatMap(crossinline block: suspend (T) -> RichResult<R>) =
    this
        .map { result ->
            result.flatMap {
                block(it)
            }
        }

fun <T> Flow<RichResult<T>>.withTerminalStateInLoading() = this
    .scan(null as RichResult<T>?) { old, new ->
        when (new) {
            is RichResult.Loading<T> -> {
                val lastTerminalState = when (old) {
                    null,
                    is RichResult.Failure<T>,
                    is RichResult.Success<T>,
                    -> old

                    is RichResult.Loading<T> -> old.lastTerminalState
                }
                if (lastTerminalState !== new.lastTerminalState) {
                    new.copy(lastTerminalState = lastTerminalState)
                } else {
                    new
                }
            }

            else -> new
        }
    }
    .filterNotNull()
