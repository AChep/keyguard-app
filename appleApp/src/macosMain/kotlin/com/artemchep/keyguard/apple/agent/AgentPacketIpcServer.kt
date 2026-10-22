package com.artemchep.keyguard.apple.agent

import com.artemchep.keyguard.common.service.agent.AgentPacketSession
import com.artemchep.keyguard.common.service.agent.serve
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serves a [session] per connection of the helper, one packet at a time. With
 * an [ioTimeoutMs], every read, including the wait for the next request, and
 * every write gives up after that long and closes the connection.
 */
internal class AgentPacketIpcServer(
    path: String,
    authToken: ByteArray,
    private val childPid: () -> Int?,
    maxConnections: Int,
    private val ioTimeoutMs: Long?,
    private val onConnectionFailure: () -> Unit = {},
    private val session: (authToken: ByteArray) -> AgentPacketSession<*, *>,
) : AgentHelperIpcServer {
    private val listener = AgentIpcListener(path, maxConnections)
    private val token = authToken.copyOf()

    val path: String get() = listener.path

    override suspend fun serve() = listener.serve(
        childPid = childPid,
        onConnectionFailure = onConnectionFailure,
        handle = ::handle,
    )

    private suspend fun handle(client: Int) {
        val session = session(token)
        session.serve(
            readPacket = { withIoTimeout { readPacket(client, session.maxPacketSize) } },
            writePacket = { packet -> withIoTimeout { writePacket(client, packet, session.maxPacketSize) } == true },
        )
    }

    private suspend fun <T> withIoTimeout(block: suspend () -> T): T? = when (val timeout = ioTimeoutMs) {
        null -> block()
        else -> withTimeoutOrNull(timeout) { block() }
    }

    override fun close() {
        listener.close()
        token.fill(0)
    }
}
