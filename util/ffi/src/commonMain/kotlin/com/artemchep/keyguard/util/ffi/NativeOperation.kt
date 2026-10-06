package com.artemchep.keyguard.util.ffi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resumeWithException

/**
 * Runs one blocking native call on [Dispatchers.IO] with a handle that lives only
 * for this call.
 *
 * [create] returning 0 means busy and fails with [busy]. Cancelling the caller
 * calls [cancel] from the cancelling thread while [execute] blocks. [close] runs
 * once [execute] returns or throws. A result that never reaches the caller goes to
 * [clear], possibly twice, so [clear] must be idempotent.
 */
@Suppress("LongParameterList") // Each bridge supplies its own handle functions and failure.
suspend fun <T : Any> runNativeOperation(
    create: () -> Long,
    cancel: (handle: Long) -> Unit,
    close: (handle: Long) -> Unit,
    busy: () -> Throwable,
    clear: (result: T) -> Unit,
    execute: (handle: Long) -> T,
): T = runNativeOperation(Dispatchers.IO, create, cancel, close, busy, clear, execute)

// Takes the context so tests can reproduce a result that withContext discards.
@Suppress("LongParameterList", "TooGenericExceptionCaught") // Completes the caller on any bridge failure.
internal suspend fun <T : Any> runNativeOperation(
    context: CoroutineContext,
    create: () -> Long,
    cancel: (handle: Long) -> Unit,
    close: (handle: Long) -> Unit,
    busy: () -> Throwable,
    clear: (result: T) -> Unit,
    execute: (handle: Long) -> T,
): T {
    var produced: T? = null
    return try {
        withContext(context) {
            suspendCancellableCoroutine { continuation ->
                val handle = create()
                if (handle == 0L) {
                    continuation.resumeWithException(busy())
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation { cancel(handle) }
                try {
                    val result = execute(handle)
                    produced = result
                    continuation.resume(result) { _, value, _ -> clear(value) }
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                } finally {
                    close(handle)
                }
            }
        }
    } catch (error: Throwable) {
        // withContext can discard a completed result while dispatching back to the caller.
        produced?.let(clear)
        throw error
    }
}
