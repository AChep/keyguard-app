package com.artemchep.keyguard.common.io

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] minus the two throwables that must never be swallowed: a
 * [CancellationException], because swallowing it keeps work running after its
 * scope died, and an [Error], because the process is no longer in a state this
 * code can reason about. Prefer it over a bare [runCatching] anywhere a failure
 * is meant to degrade into a value.
 */
inline fun <R> runCatchingNonFatal(block: () -> R): Result<R> =
    runCatching(block).onFailure(Throwable::throwIfFatalOrCancellation)

/**
 * [runCatchingNonFatal] with exactly two deliberate exceptions: a
 * [StackOverflowError] and an [OutOfMemoryError] become failures instead of
 * propagating.
 *
 * **Only for a boundary that parses a whole document supplied by another
 * application**, where attacker-controlled nesting or an oversized payload
 * provokes either one on otherwise well-formed input and the half-built result
 * is discarded whole. Anywhere else both mean the process is broken, so call
 * sites are allow-listed by the `ForbiddenImport` entry in
 * `config/detekt/detekt.yml`.
 */
inline fun <R> runCatchingUntrustedInput(block: () -> R): Result<R> =
    runCatching(block).onFailure { e ->
        if (e.isHostileInputError()) {
            return@onFailure
        }
        e.throwIfFatalOrCancellation()
    }

/**
 * The two [Error]s that [runCatchingUntrustedInput] — and nothing else —
 * treats as a property of the input rather than of the process.
 *
 * `expect` because `StackOverflowError` has no common-stdlib name: on the JVM it
 * is `java.lang.StackOverflowError`, while Kotlin/Native traps a blown stack in
 * the OS rather than raising anything catchable, so only the heap half of the
 * pair is representable there.
 */
@PublishedApi
internal expect fun Throwable.isHostileInputError(): Boolean
