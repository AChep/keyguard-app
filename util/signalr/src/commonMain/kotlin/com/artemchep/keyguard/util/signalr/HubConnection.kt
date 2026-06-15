package com.artemchep.keyguard.util.signalr

import kotlinx.coroutines.flow.Flow

interface HubConnection {
    fun events(): Flow<HubConnectionEvent>
}

sealed interface HubConnectionEvent {
    data class StateChanged(
        val state: HubConnectionState,
        val connectionId: String? = null,
        val reason: HubConnectionCloseReason? = null,
    ) : HubConnectionEvent

    data class InvocationReceived(
        val invocation: HubMessage.Invocation,
    ) : HubConnectionEvent
}

sealed interface HubConnectionCloseReason {
    data object ClientStopped : HubConnectionCloseReason
    data object TransportClosed : HubConnectionCloseReason

    data class ServerClosed(
        val error: String?,
        val allowReconnect: Boolean,
    ) : HubConnectionCloseReason

    data class Failed(
        val cause: Throwable,
    ) : HubConnectionCloseReason
}
