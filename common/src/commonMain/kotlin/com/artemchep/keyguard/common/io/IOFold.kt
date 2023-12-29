package com.artemchep.keyguard.common.io

suspend inline fun <T, R> IO<T>.fold(
    ifLeft: (Throwable) -> R,
    ifRight: (T) -> R,
): R = try {
    bind().let(ifRight)
} catch (e: Throwable) {
    e.throwIfFatalOrCancellation()
    ifLeft(e)
}
