package com.artemchep.keyguard.apple.agent

import com.artemchep.keyguard.common.service.agent.AgentByteStream
import com.artemchep.keyguard.util.agentipc.AgentIpcConnection
import com.artemchep.keyguard.util.agentipc.AgentIpcEvent
import com.artemchep.keyguard.util.agentipc.AgentIpcHost
import com.artemchep.keyguard.util.agentipc.AgentIpcListener
import com.artemchep.keyguard.util.agentipc.AgentIpcLocation

/** The bytes of this connection. Cancelling any call closes the connection. */
internal fun AgentIpcConnection.asAgentByteStream(): AgentByteStream = AgentByteStream(
    read = this::read,
    write = this::write,
    awaitInputOrDisconnect = this::awaitInputOrDisconnect,
)

/**
 * Creates a helper's private endpoint in a new run directory under [parent], which
 * exists once this returns, and the host that serves it. Every ERROR event, such as
 * a rejected peer or a failed session, is reported to [onConnectionFailure]; events
 * hold no bytes, and the report holds nothing at all.
 */
internal fun bindAgentHelperIpcHost(
    parent: String,
    maxConnections: Int,
    onConnectionFailure: () -> Unit = {},
): AgentIpcHost {
    val listener = AgentIpcListener.bind(AgentIpcLocation.UnixDirectory(parent = parent))
    return AgentIpcHost(listener, maxConnections) { event ->
        if (event.severity == AgentIpcEvent.Severity.ERROR) {
            onConnectionFailure()
        }
    }
}
