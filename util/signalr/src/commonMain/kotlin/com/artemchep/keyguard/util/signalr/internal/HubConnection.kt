package com.artemchep.keyguard.util.signalr.internal

import com.artemchep.keyguard.util.signalr.HubConnection
import com.artemchep.keyguard.util.signalr.HubConnectionEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow

internal class DefaultHubConnection internal constructor(
    private val options: HubConnectionOptions,
) : HubConnection {
    override fun events(): Flow<HubConnectionEvent> = channelFlow {
        runHubConnectionController(
            scope = this,
            events = this,
            options = options,
        )
    }.buffer(options.eventBufferCapacity)
}
