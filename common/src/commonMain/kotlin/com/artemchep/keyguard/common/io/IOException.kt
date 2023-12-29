package com.artemchep.keyguard.common.io

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Wraps the [IO] into a virtual try catch block that guarantees that
 * executing it will not result in a crash.
 */
fun <T> IO<T>.attempt(): IO<Either<Throwable, T>> = ioEffect {
    try {
        invoke().right()
    } catch (e: Throwable) {
        e.throwIfFatalOrCancellation()
        e.left()
    }
}

inline fun <T> IO<T>.retry(crossinline retry: suspend (Throwable, Int) -> Boolean): IO<T> =
    ioEffect {
        var counter = 0
        while (true) {
            try {
                return@ioEffect invoke()
            } catch (e: Throwable) {
                e.throwIfFatalOrCancellation()

                // Ask the host whether we should
                // retry or not.
                val shouldExit = !retry(e, counter)
                if (shouldExit) {
                    throw e
                }

                counter += 1
            }
        }

        @Suppress("ThrowableNotThrown", "UNREACHABLE_CODE")
        throw IllegalStateException()
    }

fun <T> Either<Throwable, T>.toIO(): IO<T> =
    fold(
        ifLeft = ::ioRaise,
        ifRight = ::io,
    )

inline fun <T> IO<T>.finally(
    crossinline block: () -> Unit,
): IO<T> = ioEffect {
    try {
        invoke()
    } finally {
        block()
    }
}

inline fun <T> IO<T>.handleErrorWith(
    crossinline predicate: (Throwable) -> Boolean = { true },
    crossinline block: (Throwable) -> IO<T>,
): IO<T> = ioEffect {
    try {
        invoke()
    } catch (e: Throwable) {
        e.throwIfFatalOrCancellation()
        // run back-up plan
        if (predicate(e)) {
            val io = block(e)
            io()
        } else {
            throw e
        }
    }
}

inline fun <T> IO<T>.handleError(
    crossinline predicate: (Throwable) -> Boolean = { true },
    crossinline block: (Throwable) -> T,
): IO<T> = ioEffect {
    try {
        invoke()
    } catch (e: Throwable) {
        e.throwIfFatalOrCancellation()
        // run back-up plan
        if (predicate(e)) {
            block(e)
        } else {
            throw e
        }
    }
}

inline fun <T> IO<T>.handleErrorTap(
    crossinline predicate: (Throwable) -> Boolean = { true },
    crossinline block: suspend (Throwable) -> Unit,
): IO<T> = ioEffect {
    try {
        invoke()
    } catch (e: Throwable) {
        e.throwIfFatalOrCancellation()
        // run back-up plan
        if (predicate(e)) {
            try {
                block(e)
            } catch (e: Throwable) {
                e.throwIfFatalOrCancellation()
            }
        }
        // throw
        throw e
    }
}

fun Throwable.throwIfFatalOrCancellation() {
    if (this is CancellationException) {
        throw this
    }
    nonFatalOrThrow()
}

private fun Throwable.nonFatalOrThrow(): Throwable =
    if (this !is Error) this else throw this

inline fun <T, R> IO<T>.bracket(
    crossinline release: (T) -> IO<Unit>,
    crossinline use: (T) -> IO<R>,
): IO<R> = ioEffect {
    val nothing = Any()
    var resource: Any? = nothing
    try {
        val r = bind().also { resource = it }
        use(r)()
    } finally {
        val res = resource
        if (res !== nothing) {
            // Always run the release IO
            withContext(NonCancellable) {
                val io = release(res as T)
                io.bind()
            }
        }
    }
}
