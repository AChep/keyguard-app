package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.common.service.agent.AgentIpcServerHost
import com.artemchep.keyguard.common.service.agent.asAgentByteStream
import com.artemchep.keyguard.common.service.agent.serveCliAgentConnection
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.util.agentipc.AgentIpcConnection
import com.artemchep.keyguard.util.agentipc.AgentIpcExpectedPeer
import com.artemchep.keyguard.util.agentipc.AgentIpcLocation

/**
 * IPC server of the app's private channel to the CLI agent. Each connection
 * authenticates, then makes exactly one request.
 *
 * Holds its own copy of the token until [close].
 */
class CliAgentIpcServer(
    logRepository: LogRepository,
    authToken: ByteArray,
    private val handler: CliAgentRequestHandler,
    maxConcurrentConnections: Int = MAX_CONCURRENT_CONNECTIONS,
    /** Bounds each read, write and request of a connection; past it, the connection is closed. */
    private val ioTimeoutMs: Long = IO_TIMEOUT_MS,
) {
    companion object {
        private const val TAG = "CliAgentIpc"

        // Mount long polls and interactive requests each own a connection.
        private const val MAX_CONCURRENT_CONNECTIONS = 32

        /**
         * The CLI agent gives up on a request after 70 s
         * (`desktopCliAgent/src/src/app_client.rs`), so a connection that
         * takes longer has no one waiting for it.
         */
        internal const val IO_TIMEOUT_MS = 70_000L
    }

    private val host = AgentIpcServerHost(
        logRepository = logRepository,
        tag = TAG,
        maxConnections = maxConcurrentConnections,
    )

    private val tokenLock = Any()

    // Guarded by tokenLock; zeroed on close.
    private val token = authToken.copyOf()
    private var closed = false

    /**
     * Creates the private endpoint at [location], which exists once this
     * returns, and returns the value of the helper's `--ipc-socket`.
     */
    fun bind(location: AgentIpcLocation): String = host.bind(location)

    /**
     * Serves the helper's connections until [close]. Accepts nothing before
     * [expectedPeer] returns, and only connections of that process.
     */
    suspend fun serve(expectedPeer: suspend () -> AgentIpcExpectedPeer) {
        host.serve(expectedPeer, ::serveConnection)
    }

    /** Closes the endpoint and every connection, and erases the token. */
    fun close() {
        host.close()
        synchronized(tokenLock) {
            closed = true
            token.fill(0)
        }
    }

    /**
     * A session with its own copy of the token, or null once the server is
     * closed: a zeroed token must never authenticate anyone.
     */
    private fun createSession(): CliAgentPacketSession? = synchronized(tokenLock) {
        if (closed) null else CliAgentPacketSession(handler, token)
    }

    private suspend fun serveConnection(connection: AgentIpcConnection) {
        val session = createSession()
            ?: return
        try {
            // The session turns every failure of a request into `invalid_request`,
            // so no error of this connection holds its bytes. A phase that
            // outlasts the client's budget ends the connection quietly.
            serveCliAgentConnection(
                stream = connection.asAgentByteStream(),
                maxPacketSize = CliAgentPacketSession.MAX_PACKET_SIZE,
                process = session::process,
                ioTimeoutMs = ioTimeoutMs,
            )
        } finally {
            session.close()
        }
    }
}
