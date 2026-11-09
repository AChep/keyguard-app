package com.artemchep.keyguard.feature.p2psync.model

import com.artemchep.keyguard.feature.p2psync.transport.Compressor
import kotlin.concurrent.Volatile

/**
 * The local [roster] and [envelope] as the sync sessions send them: the roster in ROSTER
 * messages, the envelope in ENVELOPE ones. The canonical bytes of the envelope and their
 * content hash are computed once, here, and shared by every session.
 *
 * @param hasher digests the content hash, see [EnvelopeCodec.hashOf].
 */
class LocalEnvelope(
    val roster: Roster,
    val envelope: Envelope,
    hasher: (ByteArray) -> ByteArray,
) {
    /** The canonical JSON bytes of [envelope], as [EnvelopeCodec.encode] writes them and the sessions send them. */
    internal val bytes: ByteArray = EnvelopeCodec.encode(envelope)

    /** The content hash of the [bytes]. */
    val contentHash: String = EnvelopeCodec.hashOf(bytes, hasher)

    /** `true` if the [bytes] are within [EnvelopeCodec.limits], so a peer parses them. */
    private val withinLimits: Boolean = EnvelopeCodec.limits.admits(bytes)

    /**
     * `true` if a peer takes the envelope: its [bytes] are at most [maxEnvelopeSize] and
     * within [EnvelopeCodec.limits].
     */
    internal fun fits(maxEnvelopeSize: Int): Boolean = withinLimits && bytes.size <= maxEnvelopeSize

    private class Compressed(
        val compressor: Compressor,
        val bytes: ByteArray,
    )

    /** The [bytes] as the last [compressed] call compressed them. */
    @Volatile
    private var compressed: Compressed? = null

    /**
     * Returns the [bytes] compressed with [compressor]. Every session sends the same bytes,
     * so they are compressed once; two sessions that ask at once may both compress them.
     */
    internal fun compressed(compressor: Compressor): ByteArray {
        compressed?.takeIf { it.compressor === compressor }?.let { return it.bytes }
        return compressor.compress(bytes).also { compressed = Compressed(compressor, it) }
    }
}
