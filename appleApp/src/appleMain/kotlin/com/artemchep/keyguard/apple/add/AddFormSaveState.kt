package com.artemchep.keyguard.apple.add

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.cancellation.CancellationException

/** Keeps one editor from submitting twice while its asynchronous save completes. */
internal class AddFormSaveState {
    val running = MutableStateFlow(false)

    suspend fun <T> run(save: suspend () -> T): T {
        if (!running.compareAndSet(expect = false, update = true)) {
            throw CancellationException("This form is already being saved")
        }
        return try {
            // Success leaves the gate closed until the producer dismisses this
            // editor. A delayed close callback must not permit another write.
            save()
        } catch (error: Throwable) {
            running.value = false
            throw error
        }
    }
}
