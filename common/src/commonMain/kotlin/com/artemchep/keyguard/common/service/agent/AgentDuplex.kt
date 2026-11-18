package com.artemchep.keyguard.common.service.agent

/**
 * What a private agent connection turns into once the reply to its request is
 * written: opaque packets in both directions, framed as [AgentIpcFraming]
 * describes, until either side ends the stream. It travels with that reply,
 * see [AgentPacketReply.upgrade].
 *
 * [close] runs exactly once, whether or not the stream was [served][serve]: a
 * connection that ends before its reply is written never serves it.
 */
interface AgentDuplex {
    /** The largest packet in either direction. */
    val maxPacketSize: Int

    /** Serves the stream; returning or a cancellation closes the connection. */
    suspend fun serve(channel: AgentDuplexChannel)

    /** Releases what the stream holds. */
    fun close()
}

/**
 * The packets of an [AgentDuplex]. Use it only while [AgentDuplex.serve] runs,
 * from coroutines of its own scope: once that returns, the connection is
 * closed. Both calls throw when the connection fails or is closed.
 */
interface AgentDuplexChannel {
    /**
     * The next packet, or `null` at the end of the stream. Waits for the peer
     * without a time limit; a packet whose size is outside of the stream's
     * range throws [IllegalArgumentException].
     *
     * Read from one coroutine at a time: reads that overlap break the framing.
     * A read cancelled while it waits for the first input leaves the
     * connection open; one cancelled after that may close it.
     */
    suspend fun readPacket(): ByteArray?

    /**
     * Writes [packet]. Returns `false` when the write ran out of time, which
     * closes the connection, so the stream must end. Concurrent writes keep
     * each packet whole.
     */
    suspend fun writePacket(packet: ByteArray): Boolean
}
