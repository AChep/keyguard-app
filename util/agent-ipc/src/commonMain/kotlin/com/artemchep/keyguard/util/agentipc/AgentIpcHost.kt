package com.artemchep.keyguard.util.agentipc

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

/** Delay before the next accept after one failed with [AgentIpcFailureKind.IO]. */
internal const val AGENT_IPC_ACCEPT_BACKOFF_MILLIS = 250L

/** Consecutive [AgentIpcFailureKind.IO] accept failures that end [AgentIpcHost.serve]. */
internal const val AGENT_IPC_MAX_ACCEPT_FAILURES = 20

/**
 * The accept loop of an agent helper's private endpoint, shared by every host.
 *
 * It accepts nothing before the exact helper process is known, admits at most
 * [maxConnections] connections at once and rejects the rest, and verifies each
 * peer before the handler reads a byte. It reports what happens through [onEvent],
 * whose messages are fixed text: never bytes, paths or exception messages.
 */
class AgentIpcHost internal constructor(
    private val source: AgentIpcAcceptSource,
    private val maxConnections: Int,
    private val onEvent: (AgentIpcEvent) -> Unit,
) {
    constructor(
        listener: AgentIpcListener,
        maxConnections: Int,
        onEvent: (AgentIpcEvent) -> Unit = {},
    ) : this(ListenerAcceptSource(listener), maxConnections, onEvent)

    init {
        require(maxConnections > 0) { "maxConnections must be positive" }
    }

    private val lock = SynchronizedObject()

    // Guarded by the lock: once stopped, no connection is tracked any more.
    private var stopped = false
    private val live = mutableSetOf<AgentIpcConnection>()

    private val stopSignal = CompletableDeferred<Unit>()
    private val permits = Semaphore(maxConnections)

    /** The socket path or pipe name: the value of a helper's `--ipc-socket`. */
    val endpoint: String get() = source.endpoint

    /**
     * Awaits [expectedPeer] (or [stop]) before the first accept, then serves verified
     * connections, each with its own [handler] coroutine; the connection is closed
     * when the handler returns. Returns after [stop], once every handler has returned;
     * throws [AgentIpcException] on a fatal listener failure. Either way, and on
     * cancellation, the host is stopped when this returns. Call it once.
     */
    suspend fun serve(
        expectedPeer: suspend () -> AgentIpcExpectedPeer,
        handler: suspend (AgentIpcConnection) -> Unit,
    ) {
        try {
            val peer = awaitPeer(expectedPeer) ?: return
            onEvent(AgentIpcEvent.Listening)
            coroutineScope { acceptLoop(this, peer, handler) }
        } finally {
            stop()
        }
    }

    /**
     * Stops the host: releases a [serve] still waiting for its peer, closes the
     * listener and every live connection, including one accepted concurrently.
     * Idempotent, non-blocking and safe from any thread.
     */
    fun stop() {
        val connections = synchronized(lock) {
            if (stopped) return
            stopped = true
            live.toList()
        }
        stopSignal.complete(Unit)
        source.close()
        connections.forEach(AgentIpcConnection::close)
    }

    /** The expected peer, or null when the host stops first. */
    private suspend fun awaitPeer(
        expectedPeer: suspend () -> AgentIpcExpectedPeer,
    ): AgentIpcExpectedPeer? = coroutineScope {
        val peer = async { expectedPeer() }
        try {
            // Biased to the first clause: a stopped host never starts accepting.
            select<AgentIpcExpectedPeer?> {
                stopSignal.onJoin { null }
                peer.onAwait { it }
            }
        } finally {
            peer.cancel()
        }
    }

    private suspend fun acceptLoop(
        scope: CoroutineScope,
        peer: AgentIpcExpectedPeer,
        handler: suspend (AgentIpcConnection) -> Unit,
    ) {
        var failures = 0
        while (true) {
            val connection = try {
                source.accept()
            } catch (error: AgentIpcException) {
                if (error.kind == AgentIpcFailureKind.CLOSED) return
                failures = backOff(error, failures)
                null
            }
            if (connection != null) {
                failures = 0
                if (!admit(scope, connection, peer, handler)) return
            }
        }
    }

    /**
     * Reports a failed accept and waits before the next one. Returns the number of
     * consecutive failures; throws [error] once it cannot be retried.
     */
    private suspend fun backOff(error: AgentIpcException, failures: Int): Int {
        // Only listener failures reach here: INTERNAL means it can no longer accept.
        if (error.kind != AgentIpcFailureKind.IO) throw error
        val count = failures + 1
        onEvent(AgentIpcEvent.AcceptFailed(error.kind, error.diagnostic))
        if (count >= AGENT_IPC_MAX_ACCEPT_FAILURES) throw error
        // Stopping ends the wait; the next accept then sees the closed listener.
        withTimeoutOrNull(AGENT_IPC_ACCEPT_BACKOFF_MILLIS) { stopSignal.join() }
        return count
    }

    /** Serves [connection] if a slot is free. Returns false once the host is stopped. */
    private fun admit(
        scope: CoroutineScope,
        connection: AgentIpcConnection,
        peer: AgentIpcExpectedPeer,
        handler: suspend (AgentIpcConnection) -> Unit,
    ): Boolean = when {
        !permits.tryAcquire() -> {
            connection.close()
            onEvent(AgentIpcEvent.Rejected(AgentIpcEvent.Reason.OVER_LIMIT, maxConnections))
            true
        }

        !track(connection) -> {
            connection.close()
            permits.release()
            false
        }

        else -> {
            // Undispatched, so the finally block owns the connection before
            // cancellation could discard the coroutine's first dispatch.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    serveConnection(connection, peer, handler)
                } finally {
                    untrack(connection)
                    connection.close()
                    permits.release()
                }
            }
            true
        }
    }

    @Suppress("TooGenericExceptionCaught") // A failed session ends only its own connection.
    private suspend fun serveConnection(
        connection: AgentIpcConnection,
        peer: AgentIpcExpectedPeer,
        handler: suspend (AgentIpcConnection) -> Unit,
    ) {
        try {
            // Before the handler reads anything; a rejected connection is closed unread.
            val rejection = rejection(connection, peer)
            if (rejection == null) {
                handler(connection)
            } else {
                onEvent(AgentIpcEvent.Rejected(rejection))
            }
        } catch (error: AgentIpcException) {
            when (error.kind) {
                AgentIpcFailureKind.CLOSED -> Unit
                AgentIpcFailureKind.PEER_CLOSED -> onEvent(AgentIpcEvent.ClientDisconnected)
                else -> onEvent(AgentIpcEvent.ConnectionFailed(error.kind, error.diagnostic))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onEvent(AgentIpcEvent.SessionFailed(error))
        }
    }

    /**
     * Why [connection] is not the expected peer, or null when it is: its kernel
     * process ID equals the child's, and the child runs before and after sampling.
     */
    private fun rejection(
        connection: AgentIpcConnection,
        peer: AgentIpcExpectedPeer,
    ): AgentIpcEvent.Reason? {
        val peerPid = connection.peerPid
        val before = peer.currentPid()
        return when {
            peerPid == null -> AgentIpcEvent.Reason.PEER_UNVERIFIED
            before == null -> AgentIpcEvent.Reason.PEER_NOT_RUNNING
            peerPid != before -> AgentIpcEvent.Reason.PEER_NOT_EXPECTED
            // A live child keeps its process ID, so it cannot be reused meanwhile.
            peer.currentPid() != before -> AgentIpcEvent.Reason.PEER_NOT_RUNNING
            else -> null
        }
    }

    private fun track(connection: AgentIpcConnection): Boolean = synchronized(lock) {
        !stopped && live.add(connection)
    }

    private fun untrack(connection: AgentIpcConnection) {
        synchronized(lock) { live.remove(connection) }
    }
}

/**
 * What [AgentIpcHost] reports. [message] is fixed text: never bytes, paths or
 * exception messages.
 */
sealed interface AgentIpcEvent {
    val severity: Severity
    val message: String

    enum class Severity {
        INFO,
        ERROR,
    }

    /** Why a connection was closed unread. */
    enum class Reason {
        /** Every connection slot is taken. */
        OVER_LIMIT,

        /** The peer is not a verifiable process of this user. */
        PEER_UNVERIFIED,

        /** The peer is another process than the expected helper. */
        PEER_NOT_EXPECTED,

        /** The expected helper is not running, or exited while it was checked. */
        PEER_NOT_RUNNING,
    }

    /** The expected peer is known; connections are accepted from now on. */
    data object Listening : AgentIpcEvent {
        override val severity: Severity get() = Severity.INFO
        override val message: String get() = "IPC server listening"
    }

    /** A connection was closed before any read. */
    data class Rejected(
        val reason: Reason,
        val limit: Int? = null,
    ) : AgentIpcEvent {
        override val severity: Severity get() = Severity.ERROR
        override val message: String
            get() = when (reason) {
                Reason.OVER_LIMIT ->
                    "Connection rejected: too many concurrent IPC connections (limit=$limit)"

                Reason.PEER_UNVERIFIED -> "Connection rejected: the IPC peer could not be verified"
                Reason.PEER_NOT_EXPECTED -> "Connection rejected: the IPC peer is not the agent process"
                Reason.PEER_NOT_RUNNING -> "Connection rejected: the agent process is not running"
            }
    }

    /** An accept failed and is retried after a short delay. */
    data class AcceptFailed(
        val kind: AgentIpcFailureKind,
        val diagnostic: String?,
    ) : AgentIpcEvent {
        override val severity: Severity get() = Severity.ERROR
        override val message: String get() = "IPC accept failed: ${kind.name.lowercase()}"
    }

    /** The client closed its side while the session was writing to it. */
    data object ClientDisconnected : AgentIpcEvent {
        override val severity: Severity get() = Severity.INFO
        override val message: String get() = "Client disconnected"
    }

    /** The transport of one connection failed. */
    data class ConnectionFailed(
        val kind: AgentIpcFailureKind,
        val diagnostic: String?,
    ) : AgentIpcEvent {
        override val severity: Severity get() = Severity.ERROR
        override val message: String get() = "IPC connection failed: ${kind.name.lowercase()}"
    }

    /** The handler of one connection failed. */
    data class SessionFailed(
        val error: Throwable,
    ) : AgentIpcEvent {
        override val severity: Severity get() = Severity.ERROR
        override val message: String get() = "IPC session failed: ${error::class.simpleName}"
    }
}

/** Where [AgentIpcHost] takes its connections from: a listener, or a test's fake. */
internal interface AgentIpcAcceptSource {
    val endpoint: String

    /** As [AgentIpcListener.accept]. */
    suspend fun accept(): AgentIpcConnection

    /** As [AgentIpcListener.close]. */
    fun close()
}

private class ListenerAcceptSource(
    private val listener: AgentIpcListener,
) : AgentIpcAcceptSource {
    override val endpoint: String get() = listener.endpoint

    override suspend fun accept(): AgentIpcConnection = listener.accept()

    override fun close() = listener.close()
}
