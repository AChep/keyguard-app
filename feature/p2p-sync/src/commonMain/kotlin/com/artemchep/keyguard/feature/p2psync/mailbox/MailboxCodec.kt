package com.artemchep.keyguard.feature.p2psync.mailbox

import com.artemchep.keyguard.feature.p2psync.P2pJson
import com.artemchep.keyguard.feature.p2psync.model.CanonicalJson
import com.artemchep.keyguard.feature.p2psync.model.Envelope
import com.artemchep.keyguard.feature.p2psync.model.EnvelopeCodec
import com.artemchep.keyguard.feature.p2psync.model.JsonLimits
import com.artemchep.keyguard.feature.p2psync.model.LocalEnvelope
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.parseJsonObjectOrNull
import com.artemchep.keyguard.feature.p2psync.session.BlobExchange
import com.artemchep.keyguard.feature.p2psync.session.SyncSessionConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A state item to post, before it gets its expiry: the [roster], the [envelope] and the
 * missing blobs of its sender for one recipient. [tagContent] is what the tag of the item
 * covers, everything but its expiry, so the same state posted again has the same tag.
 *
 * Its envelope is the canonical one the sessions send, nested as JSON, so the recipient
 * gets the content hash of the sender, see [LocalEnvelope.contentHash].
 *
 * @param roster the validated roster of the sender.
 * @param envelope the envelope of the sender, or `null` to send the roster only.
 * @param missing the blobs the sender needs and did not find on the relay: at most
 *   [MailboxLimits.MAX_MISSING].
 */
class MailboxStateDraft(
    val address: MailboxAddress,
    roster: Roster,
    private val envelope: LocalEnvelope?,
    missing: Collection<String>,
) {
    init {
        require(missing.size <= MailboxLimits.MAX_MISSING) {
            "A state item names at most ${MailboxLimits.MAX_MISSING} missing blobs"
        }
    }

    private val content: JsonObject = JsonObject(
        buildMap {
            putAll(MailboxFields.headerOf(address))
            put(MailboxFields.ROSTER, P2pJson.encodeToJsonElement(Roster.serializer(), roster.normalized()))
            if (envelope != null) {
                val json = P2pJson.encodeToJsonElement(Envelope.serializer(), envelope.envelope.normalized())
                put(MailboxFields.ENVELOPE, json)
            }
            put(MailboxFields.MISSING, missing.distinct().sorted().toJsonArray())
        },
    )

    /** The canonical JSON of the item without its expiry: what its tag covers. */
    val tagContent: ByteArray = CanonicalJson.encodeToByteArray(content)

    /** `true` if a peer takes the envelope, as a session sends it, see [LocalEnvelope.fits]. */
    fun fits(maxEnvelopeSize: Int): Boolean = envelope == null || envelope.fits(maxEnvelopeSize)

    /** Returns the canonical JSON of the item, counting until [expiresAt]. */
    fun encode(expiresAt: Long): ByteArray =
        CanonicalJson.encodeToByteArray(JsonObject(content + (MailboxFields.EXPIRES_AT to JsonPrimitive(expiresAt))))
}

/**
 * Reads and writes the items that the devices of a sync group leave on the relay, see
 * [MailboxKind]. Sealing them is up to the caller: what comes here is opened already.
 *
 * A state item is canonical JSON:
 * `{v, kind: "state", item, from, to, expiresAt, roster, envelope?, missing}`. It names the
 * [MailboxAddress] it was posted to, and when it stops counting, in epoch milliseconds: the
 * recipient refuses one that the relay moved, or kept past its time.
 *
 * A blob item is `u8 version | bytes`: the entry that names the blob says what it is. Its
 * key is of that blob alone, so the relay can not swap it for another one, and its bytes
 * must match the hash asked for.
 */
object MailboxCodec {
    /** The bytes of the version prefix of a blob item. */
    private const val VERSION_SIZE = 1

    /** Room for the missing blobs and the header of a state item. */
    private const val LISTS_ALLOWANCE = 16 * 1024

    /** A state item nests its envelope one level deeper than a session does. */
    private val stateLimits = JsonLimits(
        maxDepth = EnvelopeCodec.MAX_DEPTH + 1,
        maxNodes = 2 * EnvelopeCodec.MAX_NODES + MailboxLimits.MAX_MISSING + 64,
    )

    /**
     * The most bytes of an opened state item with an envelope of up to [maxEnvelopeSize]
     * bytes: the envelope, a roster of [Roster.MAX_BYTES] and the missing blobs.
     */
    fun maxStateSize(maxEnvelopeSize: Int = SyncSessionConfig.MAX_ENVELOPE_SIZE): Int =
        maxEnvelopeSize + Roster.MAX_BYTES + LISTS_ALLOWANCE

    /** The most bytes of an opened blob item with a blob of up to [maxBlobSize] bytes. */
    fun maxBlobItemSize(maxBlobSize: Long): Long = VERSION_SIZE + maxBlobSize

    /**
     * Opens the state item [bytes] that the device [MailboxAddress.from] of [expected] left
     * for this device under the id [MailboxAddress.item], at [nowMs] by the clock of this
     * device. The envelope is decoded as a session decodes one, with [hasher].
     */
    fun decodeState(
        bytes: ByteArray,
        expected: MailboxAddress,
        nowMs: Long,
        hasher: (ByteArray) -> ByteArray,
        maxEnvelopeSize: Int = SyncSessionConfig.MAX_ENVELOPE_SIZE,
    ): MailboxOpening<MailboxState> {
        val json = if (bytes.size <= maxStateSize(maxEnvelopeSize) && stateLimits.admits(bytes)) {
            parseJsonObjectOrNull(bytes)?.takeIf(CanonicalJson::isCanonical).orRejected()
        } else {
            MailboxOpening.Rejected(MailboxRejection.TOO_LARGE)
        }
        return json.then { item ->
            MailboxFields
                .checkHeader(item, expected, nowMs)
                .then { expiresAt -> stateOf(item, expected, expiresAt, hasher, maxEnvelopeSize) }
        }
    }

    /** The state item [json], whose header is checked already. */
    private fun stateOf(
        json: JsonObject,
        address: MailboxAddress,
        expiresAt: Long,
        hasher: (ByteArray) -> ByteArray,
        maxEnvelopeSize: Int,
    ): MailboxOpening<MailboxState> {
        val roster = MailboxFields.decodeRosterOrNull(json[MailboxFields.ROSTER])
        val missing = MailboxFields.blobIdsOrNull(json[MailboxFields.MISSING], MailboxLimits.MAX_MISSING)
        if (roster == null || missing == null) {
            return MailboxOpening.Rejected(MailboxRejection.MALFORMED)
        }
        return MailboxFields
            .decodeEnvelope(json[MailboxFields.ENVELOPE], hasher, maxEnvelopeSize)
            .then { envelope ->
                val state = MailboxState(
                    address = address,
                    expiresAt = expiresAt,
                    roster = roster,
                    envelope = envelope,
                    missing = missing,
                )
                MailboxOpening.Valid(state)
            }
    }

    /** Returns the blob item of the blob [bytes]. */
    fun encodeBlob(bytes: ByteArray): ByteArray {
        val out = ByteArray(VERSION_SIZE + bytes.size)
        out[0] = MailboxLimits.VERSION.toByte()
        bytes.copyInto(out, destinationOffset = VERSION_SIZE)
        return out
    }

    /**
     * Opens the blob item [bytes] of the blob [hash], see [BlobExchange.hashOf], and returns the
     * blob. Its bytes must match the hash, digested with the SHA-256 [hasher], and take at
     * most [maxBlobSize].
     */
    fun decodeBlob(
        bytes: ByteArray,
        hash: String,
        hasher: (ByteArray) -> ByteArray,
        maxBlobSize: Long,
    ): MailboxOpening<ByteArray> {
        val version = bytes.firstOrNull()?.toUByte()?.toInt()
        val rejection = when {
            version == null -> MailboxRejection.MALFORMED
            version != MailboxLimits.VERSION -> MailboxRejection.UNSUPPORTED_VERSION
            bytes.size - VERSION_SIZE > maxBlobSize -> MailboxRejection.TOO_LARGE
            else -> null
        }
        if (rejection != null) return MailboxOpening.Rejected(rejection)
        val payload = bytes.copyOfRange(VERSION_SIZE, bytes.size)
        return if (BlobExchange.hashOf(hasher, payload) == hash) {
            MailboxOpening.Valid(payload)
        } else {
            MailboxOpening.Rejected(MailboxRejection.WRONG_HASH)
        }
    }
}

private fun Collection<String>.toJsonArray(): JsonArray = JsonArray(map(::JsonPrimitive))
