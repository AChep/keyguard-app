package com.artemchep.keyguard.feature.p2psync.mailbox

import com.artemchep.keyguard.feature.p2psync.decodeOrNull
import com.artemchep.keyguard.feature.p2psync.model.CanonicalJson
import com.artemchep.keyguard.feature.p2psync.model.EnvelopeCodec
import com.artemchep.keyguard.feature.p2psync.model.EnvelopeDecoding
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.session.BlobExchange
import com.artemchep.keyguard.feature.p2psync.string
import com.artemchep.keyguard.feature.p2psync.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Returns the result of [block] on the item, or the same rejection. */
internal inline fun <T, R> MailboxOpening<T>.then(block: (T) -> MailboxOpening<R>): MailboxOpening<R> = when (this) {
    is MailboxOpening.Valid -> block(item)
    is MailboxOpening.Rejected -> this
}

/** Returns the value as a valid opening, or a rejection for the [reason] if it is `null`. */
internal fun <T : Any> T?.orRejected(reason: MailboxRejection = MailboxRejection.MALFORMED): MailboxOpening<T> =
    if (this != null) MailboxOpening.Valid(this) else MailboxOpening.Rejected(reason)

/** The fields of the items of [MailboxCodec], and how each one is read. */
internal object MailboxFields {
    const val VERSION = "v"
    const val KIND = "kind"
    const val ITEM = "item"
    const val FROM = "from"
    const val TO = "to"
    const val EXPIRES_AT = "expiresAt"
    const val ROSTER = "roster"
    const val ENVELOPE = "envelope"
    const val MISSING = "missing"

    /** The fields every state item starts with. */
    fun headerOf(address: MailboxAddress): Map<String, JsonElement> = mapOf(
        VERSION to JsonPrimitive(MailboxLimits.VERSION),
        KIND to JsonPrimitive(MailboxKind.STATE.wireName),
        ITEM to JsonPrimitive(address.item),
        FROM to JsonPrimitive(address.from),
        TO to JsonPrimitive(address.to),
    )

    /**
     * Checks the header of the state item [json]: its format, that it is the item asked for,
     * from the peer to this device, and that it counts at [nowMs]. Returns when it expires,
     * in epoch milliseconds.
     */
    fun checkHeader(
        json: JsonObject,
        expected: MailboxAddress,
        nowMs: Long,
    ): MailboxOpening<Long> {
        val expiresAt = json.long(EXPIRES_AT)
        val rejection = versionRejection(json)
            ?: when {
                expiresAt == null -> MailboxRejection.MALFORMED
                else -> addressRejection(json, expected)
                    ?: MailboxRejection.EXPIRED.takeIf { nowMs > expiresAt }
            }
        return if (rejection != null) MailboxOpening.Rejected(rejection) else expiresAt.orRejected()
    }

    /** Why the item [json] is not of the format version of this build, if it is not. */
    private fun versionRejection(json: JsonObject): MailboxRejection? {
        val version = (json[VERSION] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        return when {
            version == null -> MailboxRejection.MALFORMED
            version != MailboxLimits.VERSION -> MailboxRejection.UNSUPPORTED_VERSION
            else -> null
        }
    }

    /** Why the item [json] is not the state item at the [expected] address, if it is not. */
    private fun addressRejection(
        json: JsonObject,
        expected: MailboxAddress,
    ): MailboxRejection? = when {
        json.string(KIND) != MailboxKind.STATE.wireName -> MailboxRejection.WRONG_ITEM
        json.string(ITEM) != expected.item -> MailboxRejection.WRONG_ITEM
        json.string(FROM) != expected.from -> MailboxRejection.WRONG_SENDER
        json.string(TO) != expected.to -> MailboxRejection.WRONG_RECIPIENT
        else -> null
    }

    /**
     * Decodes the envelope [element] of a state item, as a session decodes one: `null` if
     * the item has none. It is hashed as its canonical bytes, as the sender hashed them.
     *
     * An envelope of a format this build does not read, or larger than it takes, counts as
     * none too: the roster of the item still counts.
     */
    fun decodeEnvelope(
        element: JsonElement?,
        hasher: (ByteArray) -> ByteArray,
        maxEnvelopeSize: Int,
    ): MailboxOpening<EnvelopeDecoding.Valid?> {
        if (element == null || element is JsonNull) return MailboxOpening.Valid(null)
        val json = element as? JsonObject
        val bytes = json?.let(CanonicalJson::encodeToByteArray)
        return when {
            json == null || bytes == null -> MailboxOpening.Rejected(MailboxRejection.MALFORMED)
            bytes.size > maxEnvelopeSize -> MailboxOpening.Valid(null)
            else -> when (val decoding = EnvelopeCodec.decodeParsed(json, bytes, hasher)) {
                is EnvelopeDecoding.Valid -> MailboxOpening.Valid(decoding)
                is EnvelopeDecoding.UnsupportedVersion, EnvelopeDecoding.TooLarge -> MailboxOpening.Valid(null)
                EnvelopeDecoding.Malformed -> MailboxOpening.Rejected(MailboxRejection.MALFORMED)
            }
        }
    }

    fun decodeRosterOrNull(element: JsonElement?): Roster? =
        (element as? JsonObject)?.let(Roster.serializer()::decodeOrNull)

    /**
     * Returns the first [max] distinct blob ids of the list [element], in the order sent, or
     * `null` if it is no list. The items that are no blob id, and the ids past [max], are
     * ignored: a newer build may send more of them, or of another form.
     */
    fun blobIdsOrNull(element: JsonElement?, max: Int): List<String>? =
        (element as? JsonArray)
            ?.asSequence()
            ?.mapNotNull { item -> item.stringOrNull()?.takeIf(BlobExchange::isBlobId) }
            ?.distinct()
            ?.take(max)
            ?.toList()
}

/** The number field [name], or `null` if there is none or it is no integer. */
private fun JsonObject.long(name: String): Long? =
    (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
