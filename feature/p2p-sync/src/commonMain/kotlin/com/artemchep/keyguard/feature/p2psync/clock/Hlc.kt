package com.artemchep.keyguard.feature.p2psync.clock

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A hybrid logical clock stamp: the physical [wall] time in milliseconds, a logical
 * [counter] that orders events within one millisecond, and the [node] (device id) that
 * issued it.
 *
 * Stamps are totally ordered by wall time, then counter, then node, so every device agrees
 * on which of two stamps is newer. A node never issues the same stamp twice. On the wire a
 * stamp is the string `wall:counter:node`.
 */
@Serializable(with = HlcSerializer::class)
data class Hlc(
    val wall: Long,
    val counter: Int,
    val node: String,
) : Comparable<Hlc> {
    override fun compareTo(other: Hlc): Int = when {
        wall != other.wall -> wall.compareTo(other.wall)
        counter != other.counter -> counter.compareTo(other.counter)
        else -> node.compareTo(other.node)
    }

    override fun toString(): String = "$wall$SEPARATOR$counter$SEPARATOR$node"

    companion object {
        private const val SEPARATOR = ':'
        private const val PARTS = 3

        /** Sorts below every stamp a clock issues; stands in for a missing stamp. */
        val MIN: Hlc = Hlc(wall = Long.MIN_VALUE, counter = Int.MIN_VALUE, node = "")

        /** Parses the `wall:counter:node` form, or returns `null` if [text] is not a stamp. */
        fun parseOrNull(text: String): Hlc? {
            val parts = text.split(SEPARATOR, limit = PARTS)
            val wall = parts.getOrNull(0)?.toLongOrNull()
            val counter = parts.getOrNull(1)?.toIntOrNull()
            val node = parts.getOrNull(2)
            return if (wall != null && counter != null && node != null) Hlc(wall, counter, node) else null
        }
    }
}

/** Writes an [Hlc] as its compact `wall:counter:node` string. */
internal object HlcSerializer : KSerializer<Hlc> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.artemchep.keyguard.feature.p2psync.clock.Hlc", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Hlc) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Hlc {
        val text = decoder.decodeString()
        return Hlc.parseOrNull(text) ?: throw SerializationException("Invalid HLC stamp '$text'")
    }
}
