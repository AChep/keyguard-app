package com.artemchep.keyguard.feature.p2psync.model

import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.decodeOrNull
import com.artemchep.keyguard.feature.p2psync.parseJsonObjectOrNull
import com.artemchep.keyguard.feature.p2psync.session.SyncSessionConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** What [EnvelopeCodec.decode] made of its input. */
sealed interface EnvelopeDecoding {
    /**
     * The input is an envelope of the format this build reads: the [envelope], without the
     * entries that could not be decoded.
     *
     * @property contentHash the hash of the envelope as sent, see [EnvelopeCodec.hashOf]:
     *   of its bytes, with the entries and fields this build skipped.
     */
    class Valid(
        val envelope: Envelope,
        val contentHash: String,
    ) : EnvelopeDecoding

    /**
     * The input is an envelope of another format [version]: the device with the older
     * format needs an update.
     */
    data class UnsupportedVersion(val version: Int) : EnvelopeDecoding

    /**
     * The input nests deeper or holds more nodes than [EnvelopeCodec.limits] allow, so it was
     * not parsed. A sender checks the same limits, see [LocalEnvelope.fits]: asking
     * again brings the same input.
     */
    data object TooLarge : EnvelopeDecoding

    /** The input is not an envelope. */
    data object Malformed : EnvelopeDecoding
}

/**
 * Reads and writes envelopes as canonical JSON. Decoding is lenient per entry: a malformed
 * entry is skipped, and the rest of the envelope is still used. An entry of a
 * kind this build does not know, written by a newer build, is read like any other: it is
 * checked, kept and relayed, see [SyncEntry.knownKind].
 *
 * An envelope nested deeper than [MAX_DEPTH], or with a number that has no canonical form,
 * see [CanonicalJson], is rejected as a whole: no build writes one. An envelope past the
 * [limits] is not parsed at all, see [EnvelopeDecoding.TooLarge].
 */
object EnvelopeCodec {
    /** The deepest nesting of arrays and objects in an envelope, entry bodies included. */
    const val MAX_DEPTH: Int = 32

    /**
     * The most nodes of an envelope, see [JsonLimits.maxNodes]: 256 Ki. An envelope of the
     * largest size, [com.artemchep.keyguard.feature.p2psync.session.SyncSessionConfig.MAX_ENVELOPE_SIZE],
     * holds about half as many, at about 18 bytes a node; the densest envelope within the
     * limit parses to a tree of under 32 MiB.
     */
    const val MAX_NODES: Int = 256 * 1024

    /**
     * The most canonical JSON bytes of what one message carries, the entries of an envelope
     * or a roster: the largest message, [SyncSessionConfig.MAX_ENVELOPE_SIZE], less room for
     * what the message adds and what compression may add.
     */
    const val MAX_CONTENT_SIZE: Int = SyncSessionConfig.MAX_ENVELOPE_SIZE - 64 * 1024

    /**
     * The most nodes of what one message carries, see [MAX_CONTENT_SIZE]: [MAX_NODES], less
     * room for the message's own.
     */
    const val MAX_CONTENT_NODES: Int = MAX_NODES - 1024

    /** The limits of an envelope: a peer parses none past them, and none is sent. */
    val limits: JsonLimits = JsonLimits(maxDepth = MAX_DEPTH, maxNodes = MAX_NODES)

    private const val FIELD_VERSION = "v"
    private const val FIELD_ENTRIES = "entries"

    /** Encodes [envelope] as canonical JSON UTF-8 bytes, its entries in canonical order. */
    fun encode(envelope: Envelope): ByteArray =
        CanonicalJson.encodeToByteArray(Envelope.serializer(), envelope.normalized())

    /**
     * Returns the content hash of an envelope as sent: the lowercase hex [hasher] digest of
     * its [bytes]. The receiver hashes the bytes it decoded, so the hash covers every field
     * and entry, also the ones this build does not know.
     */
    fun hashOf(bytes: ByteArray, hasher: (ByteArray) -> ByteArray): String = P2pEncoding.hex(hasher(bytes))

    /** Decodes an envelope and the content hash of its [bytes], digested with [hasher]. */
    fun decode(bytes: ByteArray, hasher: (ByteArray) -> ByteArray): EnvelopeDecoding = if (limits.admits(bytes)) {
        decode(parseJsonObjectOrNull(bytes), bytes, hasher)
    } else {
        EnvelopeDecoding.TooLarge
    }

    /**
     * Decodes the envelope [json], already parsed from its canonical [bytes], such as one
     * nested in another message, as [decode] does: the bytes are not parsed again.
     */
    internal fun decodeParsed(
        json: JsonObject,
        bytes: ByteArray,
        hasher: (ByteArray) -> ByteArray,
    ): EnvelopeDecoding = if (limits.admits(bytes)) {
        decode(json, bytes, hasher)
    } else {
        EnvelopeDecoding.TooLarge
    }

    /** Decodes the envelope [json] parsed from [bytes], or `null` if they are not a JSON object. */
    private fun decode(json: JsonObject?, bytes: ByteArray, hasher: (ByteArray) -> ByteArray): EnvelopeDecoding {
        val version = json?.get(FIELD_VERSION)?.let { it as? JsonPrimitive }?.intOrNull
        val entries = json?.get(FIELD_ENTRIES) as? JsonArray
        return when {
            json == null -> EnvelopeDecoding.Malformed
            version != null && version != Envelope.VERSION -> EnvelopeDecoding.UnsupportedVersion(version)
            entries == null || !CanonicalJson.isCanonical(json) -> EnvelopeDecoding.Malformed
            else -> EnvelopeDecoding.Valid(
                envelope = Envelope(entries = decodeEntries(entries)),
                contentHash = hashOf(bytes, hasher),
            )
        }
    }

    /**
     * Decodes an envelope and its content hash, digested with [hasher], or returns `null`
     * if the bytes are not an envelope at all or its format version is not [Envelope.VERSION].
     */
    fun decodeOrNull(bytes: ByteArray, hasher: (ByteArray) -> ByteArray): EnvelopeDecoding.Valid? =
        decode(bytes, hasher) as? EnvelopeDecoding.Valid

    /**
     * Decodes the entries [json] nested in another message, such as an enrollment bundle,
     * as leniently as [decode]. Check the limits and the canonical form of the whole
     * message, see [parseObjectOrNull], before.
     */
    internal fun decodeEntries(json: JsonArray): List<SyncEntry> = json.mapNotNull(::decodeEntryOrNull)

    /**
     * Parses [bytes] as a JSON object, or returns `null` if they are not one or are past the
     * [limits]: the parser recurses into nested arrays, and its tree takes many times the
     * bytes, so the limits are checked before it runs.
     */
    internal fun parseObjectOrNull(bytes: ByteArray, limits: JsonLimits): JsonObject? =
        if (limits.admits(bytes)) parseJsonObjectOrNull(bytes) else null

    private fun decodeEntryOrNull(raw: JsonElement): SyncEntry? = SyncEntry.serializer().decodeOrNull(raw)
}
