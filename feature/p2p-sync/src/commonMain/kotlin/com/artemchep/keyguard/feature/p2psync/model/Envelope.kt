package com.artemchep.keyguard.feature.p2psync.model

import kotlinx.serialization.Serializable

/**
 * The synced entries of a device, as sent to a peer. The sender is the peer of the session
 * it arrives over, and the roster travels in ROSTER messages of its own, see
 * [com.artemchep.keyguard.feature.p2psync.session.SyncSessionStateMachine].
 *
 * Its content hash is the SHA-256 of its bytes as sent, see [EnvelopeCodec.hashOf]. A
 * device sends it as [EnvelopeCodec.encode] writes it, so two devices with the same
 * entries send the same bytes.
 *
 * @property v the envelope format version, [VERSION].
 */
@Serializable
data class Envelope(
    val v: Int = VERSION,
    val entries: List<SyncEntry>,
) {
    /** Returns this envelope with its entries in canonical order. */
    fun normalized(): Envelope = copy(
        entries = entries.map { it.normalized() }.sortedWith(compareBy({ it.id }, { it.kind }, { it.stamp })),
    )

    companion object {
        /** The envelope format this build writes and reads. */
        const val VERSION: Int = 1
    }
}
