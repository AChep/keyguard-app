package com.artemchep.keyguard.apple.agent

import com.artemchep.keyguard.common.service.agent.AgentPacketSession
import com.artemchep.keyguard.common.service.agent.serveFramed
import com.artemchep.keyguard.util.agentipc.AgentIpcConnection
import com.artemchep.keyguard.util.agentipc.AgentIpcExpectedPeer
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Serves a [session] per connection of the helper, one packet at a time, over a
 * private endpoint in a new run directory under [parent]. With an [ioTimeoutMs],
 * every read, including the wait for the next request, and every write gives up
 * after that long and closes the connection. Every rejected connection and failed
 * session is reported to [onConnectionFailure].
 */
internal class AgentPacketIpcServer(
    parent: String,
    authToken: ByteArray,
    maxConnections: Int,
    private val ioTimeoutMs: Long?,
    onConnectionFailure: () -> Unit = {},
    private val session: (authToken: ByteArray) -> AgentPacketSession<*, *>,
) : AgentHelperIpcServer {
    private val host = bindAgentHelperIpcHost(parent, maxConnections, onConnectionFailure)

    private val lock = SynchronizedObject()

    // Guarded by the lock; zeroed on close.
    private val token = authToken.copyOf()
    private var closed = false

    override val endpoint: String get() = host.endpoint

    override suspend fun serve(expectedPeer: suspend () -> AgentIpcExpectedPeer) {
        host.serve(expectedPeer, ::serveConnection)
    }

    private suspend fun serveConnection(connection: AgentIpcConnection) {
        val packetSession = createSession()
            ?: return
        packetSession.serveFramed(connection.asAgentByteStream(), ioTimeoutMs)
    }

    /**
     * A session with its own copy of the token, or null once the server is
     * closed: a zeroed token must never authenticate anyone.
     */
    private fun createSession(): AgentPacketSession<*, *>? = synchronized(lock) {
        if (closed) null else session(token)
    }

    override fun close() {
        host.stop()
        synchronized(lock) {
            closed = true
            token.fill(0)
        }
    }
}
