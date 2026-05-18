package com.artemchep.keyguard.util.signalr.internal

import com.artemchep.keyguard.util.signalr.HubConnectionCloseReason
import com.artemchep.keyguard.util.signalr.HubMessage
import com.artemchep.keyguard.util.signalr.internal.util.EstablishedConnection

internal sealed interface HubConnectionCommand {
    data object Start : HubConnectionCommand

    data class ConnectSucceeded(
        val sessionId: Long,
        val connection: EstablishedConnection,
    ) : HubConnectionCommand

    data class ConnectFailed(
        val sessionId: Long,
        val cause: Throwable,
    ) : HubConnectionCommand

    data class SessionClosed(
        val sessionId: Long,
        val reason: HubConnectionCloseReason,
    ) : HubConnectionCommand

    data class InvocationReceived(
        val sessionId: Long,
        val invocation: HubMessage.Invocation,
    ) : HubConnectionCommand
}
