package com.artemchep.keyguard.util.agentipc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resumeWithException

/**
 * The threads that block in native agent IPC calls. Each pending call holds one:
 * two lanes per connection for at most 8 + 8 + 32 connections, plus one accept per
 * listener, stay below the limit.
 */
internal val AgentIpcDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(128)

/**
 * Runs one blocking native call on [context], where cancellation destroys: the
 * only way to interrupt a native call is to close the object it blocks on.
 *
 * Cancelling the caller runs [close] from the cancelling thread while [execute]
 * blocks; the handler is registered before [execute] starts. A caller cancelled
 * before [execute] runs also gets [close]. A result that never reaches the caller,
 * because the caller was cancelled while it was produced or while it was dispatched
 * back, goes to [clear], possibly twice, so [clear] must be idempotent.
 */
@Suppress("TooGenericExceptionCaught") // Completes the caller on any failure of the call.
internal suspend fun <T : Any> agentIpcCall(
    close: () -> Unit,
    clear: (T) -> Unit = {},
    context: CoroutineContext = AgentIpcDispatcher,
    execute: () -> T,
): T {
    var produced: T? = null
    return try {
        withContext(context) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { close() }
                try {
                    val result = execute()
                    produced = result
                    continuation.resume(result) { _, value, _ -> clear(value) }
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            }
        }
    } catch (error: Throwable) {
        // withContext can discard a completed result while dispatching back to the caller.
        produced?.let(clear)
        // Also when cancellation came before the call was dispatched at all.
        if (error is CancellationException) close()
        throw error
    }
}
