package com.artemchep.keyguard.util.agentipc

import kotlinx.atomicfu.atomic

/**
 * An accepted connection. Its inbound lane ([read] and [awaitInputOrDisconnect]) and
 * its outbound lane ([write]) run at the same time; a second call on a busy lane is
 * a bug and throws [IllegalStateException].
 *
 * Cancelling any call closes the connection: there is no other way to interrupt a
 * native call. So a timeout around a call ends the connection too.
 *
 * Data written before [close] stays readable by the peer.
 */
class AgentIpcConnection internal constructor(
    private val handle: Long,
    /**
     * The peer's process ID from the kernel snapshot taken at accept, before any I/O,
     * or null when the peer is not a verifiable process of this user.
     */
    val peerPid: Long?,
) : AutoCloseable {
    private val closed = atomic(false)

    /**
     * Reads at least one byte into [buffer], at most [length] and at most 1 MiB, and
     * returns the count. Returns -1 at the end of the stream. [length] must be at
     * least 1. Cancelling closes the connection.
     *
     * Throws [AgentIpcException]: [AgentIpcFailureKind.CLOSED] after a local close,
     * [AgentIpcFailureKind.INVALID_ARGUMENT] for an invalid range.
     */
    suspend fun read(
        buffer: ByteArray,
        offset: Int = 0,
        length: Int = buffer.size - offset,
    ): Int = agentIpcCall(close = this::close) {
        when (val count = NativeAgentIpc.read(handle, buffer, offset, length).checkedAgentIpcResult()) {
            0L -> -1
            else -> count.toInt()
        }
    }

    /**
     * Writes all [length] bytes of [buffer] from [offset], at most 32 MiB. An empty
     * range returns at once. Cancelling closes the connection.
     *
     * Throws [AgentIpcException]: [AgentIpcFailureKind.PEER_CLOSED] when the peer
     * closed its side, [AgentIpcFailureKind.CLOSED] after a local close.
     */
    suspend fun write(
        buffer: ByteArray,
        offset: Int = 0,
        length: Int = buffer.size - offset,
    ) {
        agentIpcCall(close = this::close) {
            NativeAgentIpc.write(handle, buffer, offset, length).checkedAgentIpcResult()
        }
    }

    /**
     * Returns once the first unread byte arrives, without consuming it, or at the end
     * of the stream, on any failure, or after a local close. Cancelling closes the
     * connection.
     *
     * Throws only [kotlinx.coroutines.CancellationException], and
     * [IllegalStateException] when the inbound lane is busy, which is a bug.
     */
    suspend fun awaitInputOrDisconnect() {
        try {
            agentIpcCall(close = this::close) {
                NativeAgentIpc.awaitInput(handle).checkedAgentIpcResult()
            }
        } catch (_: AgentIpcException) {
            // The connection ended, for whatever reason.
        }
    }

    /**
     * Wakes every pending call, which then fails with [AgentIpcFailureKind.CLOSED].
     * Idempotent, non-blocking and safe from any thread.
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // The handle is consumed at most once here; a closing failure leaves
            // nothing to recover, and this runs in cancellation handlers.
            NativeAgentIpc.close(handle)
        }
    }

    internal companion object {
        /** Wraps an accepted [handle] with its peer snapshot. */
        fun accepted(handle: Long): AgentIpcConnection {
            // -7 when the peer cannot be verified. Any other failure is treated the
            // same way: the host rejects a connection without a peer process ID.
            val pid = NativeAgentIpc.peerPid(handle)
            return AgentIpcConnection(handle, pid.takeIf { it > 0L })
        }
    }
}
