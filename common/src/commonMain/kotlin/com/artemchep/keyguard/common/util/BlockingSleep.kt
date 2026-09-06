package com.artemchep.keyguard.common.util

/**
 * Blocks the current thread for [milliseconds].
 *
 * This function is intended for synchronous code and does not cooperate with
 * coroutine cancellation. Suspending code should use `kotlinx.coroutines.delay`
 * instead.
 *
 * @return `true` if the wait completed normally, or `false` if it was interrupted
 * or the platform sleep operation failed.
 * @throws IllegalArgumentException if [milliseconds] is negative.
 */
internal expect fun sleepBlocking(milliseconds: Long): Boolean
