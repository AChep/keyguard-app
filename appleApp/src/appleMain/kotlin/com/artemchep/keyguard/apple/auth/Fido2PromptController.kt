package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.feature.fido2.Fido2Prompt
import com.artemchep.keyguard.util.fido2.Fido2Exception
import com.artemchep.keyguard.util.fido2.Fido2Failure
import com.artemchep.keyguard.util.fido2.Fido2Operation
import com.artemchep.keyguard.util.fido2.NativeFido2Client
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Main-confined native prompt state. The PIN is never persisted or published in snapshots. */
internal class Fido2PromptController(private val ctx: CoreContext) {
    private val state = MutableStateFlow(Fido2PromptPhase.HIDDEN)
    private var operationJob: Job? = null
    private var pinChannel: Channel<String>? = null

    fun observe(onChange: (Fido2PromptPhase) -> Unit) =
        ctx.launchObserver { state.collectOnMain(onChange) }

    fun submitPin(pin: String) {
        if (
            state.value == Fido2PromptPhase.PIN_REQUIRED ||
                state.value == Fido2PromptPhase.PIN_INVALID
        )
            pinChannel?.trySend(pin)
    }

    fun cancel() {
        operationJob?.cancel()
    }

    @Suppress(
        "TooGenericExceptionCaught"
    ) // Deliver every bridge failure to the waiting unlock operation.
    suspend fun handle(prompt: Fido2Prompt) = coroutineScope {
        val job = currentCoroutineContext()[Job]!!
        val cancellation = launch {
            prompt.active.first { !it }
            job.cancel()
        }
        try {
            run { execute -> prompt.complete(Result.success(execute(prompt.operation))) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            prompt.complete(Result.failure(error))
        } finally {
            cancellation.cancel()
            prompt.cancel()
        }
    }

    /** Keep a single sheet open for both enrollment ceremonies. */
    suspend fun <T> run(block: suspend (suspend (Fido2Operation) -> ByteArray) -> T): T =
        withContext(Dispatchers.Main) {
            check(operationJob == null)
            operationJob = currentCoroutineContext()[Job]
            val pins = Channel<String>(Channel.RENDEZVOUS)
            pinChannel = pins
            state.value = Fido2PromptPhase.TOUCH
            try {
                block { operation -> execute(operation, pins) }
            } finally {
                pins.close()
                pinChannel = null
                operationJob = null
                state.value = Fido2PromptPhase.HIDDEN
            }
        }

    private suspend fun execute(operation: Fido2Operation, pins: Channel<String>): ByteArray {
        var pin: String? = null
        while (true) {
            state.value = Fido2PromptPhase.TOUCH
            try {
                return NativeFido2Client().execute(operation, pin)
            } catch (error: Fido2Exception) {
                when (error.failure) {
                    Fido2Failure.PIN_REQUIRED -> state.value = Fido2PromptPhase.PIN_REQUIRED
                    Fido2Failure.INVALID_PIN -> state.value = Fido2PromptPhase.PIN_INVALID
                    Fido2Failure.CANCELED -> throw CancellationException()
                    else -> throw error
                }
            } finally {
                pin = null
            }
            pin = pins.receive()
        }
    }
}
