package com.artemchep.keyguard.feature.navigation

import kotlinx.atomicfu.atomic

/** Claims the result before navigation can dispose the dialog and report a dismissal. */
internal class RouteResultHandler<T>(
    private val transmitter: RouteResultTransmitter<T>,
) {
    private val completed = atomic(false)

    fun complete(
        result: T,
        beforeTransmit: () -> Unit = {},
    ) {
        if (completed.compareAndSet(expect = false, update = true)) {
            beforeTransmit()
            transmitter(result)
        }
    }
}
