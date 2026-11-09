package com.artemchep.keyguard.feature.p2psync.clock

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline

/**
 * A version vector: for each writer (device id), the stamp of its latest event a version
 * includes. The stamps of one writer only grow, so the vector describes the version's
 * whole causal history. A missing writer has no event.
 *
 * The counters are the writers' own clock stamps, not numbers anyone may pick: a stamp
 * is never further in the future than [HlcClock.MAX_FUTURE_DRIFT_MS], so no vector can
 * run ahead of its writers for longer than that.
 *
 * On the wire it is the array of its stamps sorted by writer; each stamp names its writer.
 */
@Serializable(with = VersionVectorSerializer::class)
@JvmInline
value class VersionVector(
    /** The latest stamp of each writer, by writer; each stamp is of its key. */
    val stamps: Map<String, Hlc>,
) {
    init {
        require(stamps.all { (node, stamp) -> node == stamp.node }) {
            "Each stamp of a version vector must be of its writer"
        }
    }

    /** The latest stamp of [node] this version includes, or `null` if none. */
    operator fun get(node: String): Hlc? = stamps[node]

    /** `true` if this vector includes no event. */
    fun isEmpty(): Boolean = stamps.isEmpty()

    /** `true` if this version includes every event [other] includes. */
    fun covers(other: VersionVector): Boolean = other.stamps.all { (node, stamp) ->
        val own = this[node]
        own != null && own >= stamp
    }

    /** Returns the history that includes the events of both vectors. */
    fun join(other: VersionVector): VersionVector = of(stamps.values + other.stamps.values)

    /** Returns this history plus the event [stamp] of its node. */
    fun witness(stamp: Hlc): VersionVector = join(VersionVector(mapOf(stamp.node to stamp)))

    companion object {
        /** The history of nothing. */
        val EMPTY: VersionVector = VersionVector(emptyMap())

        /** Returns the history of the events [stamps]: the latest one of each writer. */
        fun of(stamps: Iterable<Hlc>): VersionVector {
            val latest = mutableMapOf<String, Hlc>()
            stamps.forEach { stamp ->
                val current = latest[stamp.node]
                if (current == null || stamp > current) latest[stamp.node] = stamp
            }
            return VersionVector(latest)
        }
    }
}

/** Writes a [VersionVector] as the array of its stamps, sorted by writer. */
internal object VersionVectorSerializer : KSerializer<VersionVector> {
    private val delegate = ListSerializer(Hlc.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: VersionVector) {
        encoder.encodeSerializableValue(delegate, value.stamps.values.sortedBy { it.node })
    }

    override fun deserialize(decoder: Decoder): VersionVector {
        val stamps = decoder.decodeSerializableValue(delegate)
        // One stamp per writer: anything else has no canonical form.
        if (stamps.distinctBy { it.node }.size != stamps.size) {
            throw SerializationException("A version vector names a writer twice")
        }
        return VersionVector(stamps.associateBy { it.node })
    }
}
