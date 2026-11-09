package com.artemchep.keyguard.provider.keyguard.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** The connection of a [RelayClient] to its room. */
sealed interface RelayState {
    /** Opening the WebSocket and joining the room. */
    data object Connecting : RelayState

    /**
     * In the room as the relay peer [self], which the relay draws for each connection.
     * [ticket] is this connection's ticket for the relay's ICE endpoint, if the relay sends
     * one, and [limits] are the ones the relay announced. A relay that keeps items for the
     * devices that are offline names their limits in [RelayLimits.mailbox]; the requests for
     * them carry the [ticket], see [RelayMailbox].
     */
    data class Joined(
        val self: String,
        val ticket: String? = null,
        val limits: RelayLimits = RelayLimits(),
    ) : RelayState

    /** Not connected; [error] tells why the last connection ended, if it failed. */
    data class Disconnected(val error: RelayError?) : RelayState
}

/**
 * A connection to one room of the relay. Peers are named by their relay peer ids, which
 * the relay draws per connection: a reconnect gives this device, and the others, new ids.
 *
 * The [state] turns [RelayState.Joined], and [presence] lists the peers of the room, before
 * the first message or frame of that connection is delivered.
 */
interface RelayClient {
    val state: StateFlow<RelayState>

    /** The relay peer ids of the other peers in the room; empty while not joined. */
    val presence: StateFlow<Set<String>>

    /**
     * The messages other peers published to this peer or to the room. Buffered until
     * collected, and each message reaches one collector: collect it once.
     */
    val messages: Flow<RelayMessage>

    /** The frames other peers of the room forwarded to this peer. Buffered; collect it once. */
    val forwarded: Flow<RelayForwardFrame>

    /** The errors the relay reports; [RelayError.RateLimited] means a frame was dropped. */
    val errors: SharedFlow<RelayError>

    /**
     * The receive credit of the relay-forwarded streams of the current connection, shared
     * by every room on it; `null` while not joined.
     */
    val forwardCredit: RelayForwardCredit?

    /**
     * Publishes [data] to the peer [to], or to every other peer with
     * [RelayCodec.BROADCAST]. A message to a peer that left is dropped by the relay.
     *
     * @throws RelayException with [RelayError.NotConnected] while not joined, or
     * [RelayError.FrameTooBig] for a message over [RelayLimits.maxMessageBytes].
     */
    suspend fun publish(to: String, data: ByteArray)

    /**
     * Forwards [payload] to the peer [to] in the room: at most [RelayLimits.maxMessageBytes]
     * less [RelayCodec.FORWARD_HEADER_SIZE] bytes. The relay drops a frame for a peer that is
     * not in it.
     *
     * @throws RelayException as [publish] does.
     */
    suspend fun forward(to: String, payload: ByteArray)

    /**
     * Suspends while the outgoing queue is full. Returns at once when not joined; the next
     * send then fails.
     */
    suspend fun awaitWritable()
}
