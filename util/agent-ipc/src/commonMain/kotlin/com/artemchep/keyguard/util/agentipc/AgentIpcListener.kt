package com.artemchep.keyguard.util.agentipc

import kotlinx.atomicfu.atomic
import kotlin.coroutines.CoroutineContext

/**
 * A listening endpoint, owned by this process. It is connectable as soon as [bind]
 * returns, so it exists before the helper that connects to it starts.
 *
 * One [accept] runs at a time; a second concurrent call is a bug and throws
 * [IllegalStateException]. [close] is idempotent, non-blocking and safe from any
 * thread; on Unix it removes the socket and its directory if they are still the
 * ones [bind] created.
 */
class AgentIpcListener private constructor(
    private val handle: Long,
    /** The socket path or pipe name: the value of a helper's `--ipc-socket`. */
    val endpoint: String,
) : AutoCloseable {
    private val closed = atomic(false)

    /**
     * Suspends until a client connects. Only listener failures throw
     * [AgentIpcException]: [AgentIpcFailureKind.CLOSED] once this listener is closed,
     * [AgentIpcFailureKind.IO] when the system runs out of resources, and
     * [AgentIpcFailureKind.INTERNAL] when it can no longer accept. A client that
     * vanished, or a peer that cannot be verified, never fails here.
     *
     * Cancelling closes this listener. A connection accepted after cancellation is
     * closed, never leaked.
     */
    suspend fun accept(): AgentIpcConnection = accept(AgentIpcDispatcher)

    // Takes the context so tests can reproduce a result that withContext discards.
    internal suspend fun accept(context: CoroutineContext): AgentIpcConnection = agentIpcCall(
        close = this::close,
        clear = AgentIpcConnection::close,
        context = context,
    ) {
        AgentIpcConnection.accepted(NativeAgentIpc.accept(handle).checkedAgentIpcResult())
    }

    /** Stops listening and wakes a pending [accept]. Accepted connections stay open. */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            NativeAgentIpc.closeListener(handle)
        }
    }

    companion object {
        /**
         * Creates and secures the endpoint at [location]. Throws [AgentIpcException]:
         * [AgentIpcFailureKind.INVALID_ARGUMENT] for an invalid name or a location of
         * the other platform, [AgentIpcFailureKind.PERMISSION] when an exact name is
         * taken or unsafe, [AgentIpcFailureKind.UNAVAILABLE] without the native library.
         */
        fun bind(location: AgentIpcLocation): AgentIpcListener {
            val handle = when (location) {
                is AgentIpcLocation.UnixDirectory -> NativeAgentIpc.listen(
                    location.parent,
                    location.prefix,
                    location.exactName,
                )

                is AgentIpcLocation.WindowsPipe -> NativeAgentIpc.listen(
                    null,
                    location.prefix,
                    location.exactName,
                )
            }.checkedAgentIpcResult()
            val endpoint = NativeAgentIpc.endpoint(handle)
            if (endpoint == null) {
                // Read the diagnostic before the next native call replaces it.
                val failure = AgentIpcException(
                    AgentIpcFailureKind.INTERNAL,
                    diagnostic = NativeAgentIpc.lastError(),
                )
                NativeAgentIpc.closeListener(handle)
                throw failure
            }
            return AgentIpcListener(handle, endpoint)
        }
    }
}
