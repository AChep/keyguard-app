package com.artemchep.keyguard.feature.fido2

import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.util.fido2.Fido2Exception
import com.artemchep.keyguard.util.fido2.Fido2Failure
import com.artemchep.keyguard.util.fido2.Fido2Operation
import kotlin.coroutines.resumeWithException
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

class Fido2Prompt(
    val operation: Fido2Operation,
    private val onComplete: (Result<ByteArray>) -> Unit,
) {
    private val completed = atomic(false)
    private val activeSink = MutableStateFlow(true)
    val active: StateFlow<Boolean> = activeSink

    fun complete(result: Result<ByteArray>) {
        if (completed.compareAndSet(expect = false, update = true)) {
            activeSink.value = false
            onComplete(result)
        } else {
            result.getOrNull()?.fill(0)
        }
    }

    fun cancel() = complete(Result.failure(Fido2Exception(Fido2Failure.CANCELED)))
}

class Fido2PromptHost {
    val events = EventFlow<Fido2Prompt>()

    suspend fun execute(operation: Fido2Operation): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val prompt =
                Fido2Prompt(operation) { result ->
                    result.fold(
                        onSuccess = { bytes ->
                            continuation.resume(bytes) { _, value, _ -> value.fill(0) }
                        },
                        onFailure = { error ->
                            if ((error as? Fido2Exception)?.failure == Fido2Failure.CANCELED) {
                                continuation.cancel()
                            } else {
                                continuation.resumeWithException(error.asFido2AppException())
                            }
                        },
                    )
                }
            continuation.invokeOnCancellation { prompt.cancel() }
            if (events.isActive) events.emit(prompt) else prompt.cancel()
        }
}
