package com.artemchep.keyguard.feature.p2psync.transport

import kotlinx.coroutines.flow.Flow

/** A signaling message from the device [from]. */
class SignalingMessage(
    val from: String,
    val payload: ByteArray,
)

/**
 * Exchanges small messages with peers through the relay, to set up connections. Peers are
 * named by their device ids. Payloads are opaque here; the application encrypts them under
 * a group-derived key.
 */
interface Signaling {
    /** The device ids of the peers present in the room. */
    val presence: Flow<Set<String>>

    /** The messages addressed to this device. */
    val incoming: Flow<SignalingMessage>

    /** Sends [payload] to the device [to]. */
    suspend fun send(to: String, payload: ByteArray)
}
