package com.artemchep.keyguard.feature.p2psync.model

import com.artemchep.keyguard.feature.p2psync.P2pJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A signed object, kept as the JSON object it was received as: [raw]. Two objects are equal
 * if they are of one class and their raw objects are equal, see [EntryVersion],
 * [EntryDelete], [RosterDevice] and [Revocation].
 */
abstract class RawObject internal constructor(
    val raw: JsonObject,
) {
    /** The canonical JSON text of [raw]: ties and equality of the object as sent. */
    internal val canonicalText: String by lazy(LazyThreadSafetyMode.PUBLICATION) { CanonicalJson.encode(raw) }

    private val hash: Int by lazy(LazyThreadSafetyMode.PUBLICATION) { raw.hashCode() }

    final override fun equals(other: Any?): Boolean = this === other ||
        other is RawObject && other::class == this::class && hash == other.hash && raw == other.raw

    final override fun hashCode(): Int = hash
}

/**
 * Writes a signed object as the JSON object it was received as, see [EntryVersion],
 * [EntryDelete], [RosterDevice] and [Revocation]. Every field is kept, also the ones this
 * build does not know: a field a newer build adds survives storage, merging and relaying,
 * and the signature over it still verifies.
 */
internal open class RawObjectSerializer<T : Any>(
    private val fromRaw: (JsonObject) -> T,
    private val rawOf: (T) -> JsonObject,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor get() = JsonObject.serializer().descriptor

    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeSerializableValue(JsonObject.serializer(), rawOf(value))
    }

    override fun deserialize(decoder: Decoder): T = fromRaw(decoder.decodeSerializableValue(JsonObject.serializer()))
}

/**
 * Returns the fields of a signed object this build reads from its [raw] object.
 *
 * @throws IllegalArgumentException if a field is malformed, or a number has no canonical
 *   form, see [CanonicalJson].
 */
internal fun <T> decodeFields(serializer: KSerializer<T>, raw: JsonObject): T {
    CanonicalJson.requireCanonical(raw)
    return P2pJson.decodeFromJsonElement(serializer, raw)
}

/** Returns the [fields] this build writes as a JSON object. */
internal fun <T> encodeFields(serializer: KSerializer<T>, fields: T): JsonObject =
    P2pJson.encodeToJsonElement(serializer, fields).jsonObject

/**
 * Returns this object with the fields of [after] that differ from [before], and without
 * the ones [after] lacks: a field a copy did not change keeps its form, and the fields this
 * build does not know stay as they are.
 */
internal fun <T> JsonObject.withChanges(serializer: KSerializer<T>, before: T, after: T): JsonObject {
    val old = encodeFields(serializer, before)
    val new = encodeFields(serializer, after)
    val changed = new.filter { (key, value) -> old[key] != value }
    val removed = old.keys - new.keys
    return if (changed.isEmpty() && removed.isEmpty()) this else JsonObject(this - removed + changed)
}
