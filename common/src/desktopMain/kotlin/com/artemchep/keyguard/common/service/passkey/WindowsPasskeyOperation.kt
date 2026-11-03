package com.artemchep.keyguard.common.service.passkey

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Coordinates native request events with completion and its successful-use bookkeeping. */
internal class WindowsPasskeyOperation(
    val id: Long,
    scope: CoroutineScope,
    block: suspend (WindowsPasskeyOperation) -> Unit,
) {
    /** Set once by whichever of completion or cancellation comes first. */
    private val settled = AtomicBoolean(false)
    private val job = scope.launch(start = CoroutineStart.LAZY) { block(this@WindowsPasskeyOperation) }

    fun start() {
        job.start()
    }

    fun cancelIfPending() {
        if (settled.compareAndSet(false, true)) {
            job.cancel()
        }
    }

    fun cancel() {
        job.cancel()
    }

    suspend fun complete(block: suspend () -> Unit) {
        currentCoroutineContext().ensureActive()
        if (!settled.compareAndSet(false, true)) {
            throw CancellationException("Passkey request cancelled")
        }
        // Native acceptance clears activeId before the JNI call resumes. The event loop must
        // allow this block to finish; vault retirement and parent cancellation still apply.
        // Windows rejects completion if cancellation or expiry won before acceptance.
        block()
    }
}
