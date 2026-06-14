package com.artemchep.keyguard.common.service.clipboard

import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.platform.WindowId
import kotlinx.coroutines.flow.Flow
import org.kodein.di.DirectDI

class ClipboardEventBus(
) {
    private val copyEventsSink = EventFlow<ClipboardEvent>()

    val copyEvents: Flow<ClipboardEvent>
        get() = copyEventsSink

    constructor(
        directDI: DirectDI,
    ) : this()

    fun post(event: ClipboardEvent) {
        copyEventsSink.emit(event)
    }
}

sealed interface ClipboardEvent {
    val windowId: WindowId

    data class Copy(
        override val windowId: WindowId,
    ) : ClipboardEvent
}
