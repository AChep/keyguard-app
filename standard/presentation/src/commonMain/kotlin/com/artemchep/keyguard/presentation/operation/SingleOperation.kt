package com.artemchep.keyguard.presentation.operation

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SingleOperationState(
    val busy: Boolean = false,
    /** The running operation can be interrupted. */
    val interruptible: Boolean = false,
    /** The last operation failed; the next one resets it. */
    val failed: Boolean = false,
)

/**
 * Runs one operation at a time in the [scope]; the next operation
 * starts after an interrupted one finishes. An operation never
 * changes the [state] after an [interrupt].
 */
class SingleOperation(
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(SingleOperationState())
    val state: StateFlow<SingleOperationState> = mutableState.asStateFlow()

    // Guards the jobs and the state: operations may finish
    // on a different thread than the one that launches them.
    private val lock = SynchronizedObject()

    private var current: Job? = null

    /** The last interrupted operation, which might still be finishing. */
    private var interrupted: Job? = null

    /** @return `false` if the operation was not started. */
    fun launch(
        interruptible: Boolean = false,
        block: suspend () -> Unit,
    ): Boolean {
        val job = synchronized(lock) {
            if (current != null || !scope.isActive) return false
            val previous = interrupted
            // Lazy, so the job is known before an unconfined
            // dispatcher runs it to the end.
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val self = currentCoroutineContext().job
                var failed = false
                try {
                    previous?.join()
                    block()
                } catch (error: CancellationException) {
                    // The operation itself might throw one, for
                    // example on a timeout.
                    if (!self.isActive) throw error
                    failed = true
                } catch (_: Exception) {
                    failed = true
                } finally {
                    // Never finish before the interrupted operation, also
                    // when interrupted while waiting for it.
                    withContext(NonCancellable) { previous?.join() }
                    synchronized(lock) {
                        if (current === self) {
                            current = null
                            mutableState.value = SingleOperationState(failed = failed)
                        }
                    }
                }
            }
            current = job
            mutableState.value = SingleOperationState(
                busy = true,
                interruptible = interruptible,
            )
            job
        }
        job.start()
        return true
    }

    /**
     * Cancels the running operation and forgets the last failure.
     *
     * @return `false` if the running operation is not interruptible.
     */
    fun interrupt(): Boolean = synchronized(lock) {
        val job = current
        if (job != null && !mutableState.value.interruptible) return false
        if (job != null) {
            job.cancel()
            interrupted = job
            current = null
        }
        mutableState.value = SingleOperationState()
        true
    }
}
