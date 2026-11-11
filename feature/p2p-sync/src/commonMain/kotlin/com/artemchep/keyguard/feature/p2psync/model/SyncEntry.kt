package com.artemchep.keyguard.feature.p2psync.model

import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.clock.VersionVector
import com.artemchep.keyguard.feature.p2psync.parts.KeyguardPartSchemas
import com.artemchep.keyguard.feature.p2psync.parts.PartAccess
import com.artemchep.keyguard.feature.p2psync.parts.PartSchema
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * What a [SyncEntry] holds. The [SerialName] of a kind names it in entries and in
 * storage, see [wireName]; it never changes.
 *
 * @property scope the scope whose groups read entries of the kind. A group of another scope
 *   keeps and relays them like any kind it does not read.
 * @property schema the parts of the body of an entry, see [com.artemchep.keyguard.feature.p2psync.parts].
 * @property internal `true` if the entries of the kind belong to the group itself, such as
 *   [OBJECT_KEY]: no local record holds them, and a device syncs them whatever else it syncs.
 */
@Serializable
enum class EntryKind(
    val scope: SyncScope,
    val schema: PartSchema,
    val internal: Boolean = false,
) {
    /** An email forwarder of the password generator, synced between linked apps. */
    @SerialName("emailForwarder")
    EMAIL_FORWARDER(
        scope = SyncScope.APP,
        schema = KeyguardPartSchemas.emailForwarder,
    ),

    /** A wordlist of the password generator, synced between linked apps. */
    @SerialName("wordlist")
    WORDLIST(
        scope = SyncScope.APP,
        schema = KeyguardPartSchemas.wordlist,
    ),

    /** A URL override, synced between linked apps; the built-in one never syncs. */
    @SerialName("urlOverride")
    URL_OVERRIDE(
        scope = SyncScope.APP,
        schema = KeyguardPartSchemas.urlOverride,
    ),

    /** A blocked URI of autofill, synced between linked apps. */
    @SerialName("urlBlock")
    URL_BLOCK(
        scope = SyncScope.APP,
        schema = KeyguardPartSchemas.urlBlock,
    ),

    /** An app the user lets request passkeys for any website, synced between linked apps. */
    @SerialName("privilegedApp")
    PRIVILEGED_APP(
        scope = SyncScope.APP,
        schema = KeyguardPartSchemas.privilegedApp,
    ),

    /** The key of the blobs the group keeps on the relay, see [ObjectKey]. */
    @SerialName("objectKey")
    OBJECT_KEY(
        scope = SyncScope.APP,
        schema = KeyguardPartSchemas.objectKey,
        internal = true,
    ),
    // Reserved: "item" and "folder" for the vaults, "setting" for linked apps.
    ;

    /** The name of the kind in entries and in storage. */
    val wireName: String get() = serializer().descriptor.getElementName(ordinal)

    companion object {
        private val byWireName by lazy { entries.associateBy { it.wireName } }

        /** Returns the kind named [wireName], or `null` if this build does not know it. */
        fun ofWireName(wireName: String): EntryKind? = byWireName[wireName]
    }
}

/**
 * One device's version of an entry: the whole record as the device saved it.
 *
 * A version is kept as its [raw] object, which its signature covers: every field, also the
 * ones this build does not know, survives storage, merging and relaying. The other
 * properties are a view over it.
 *
 * @property raw the version as signed, see [com.artemchep.keyguard.feature.p2psync.merge.EntrySigner].
 * @property stamp when the device saved it; its node is the writer.
 * @property history the latest stamp of each writer this version builds on, [stamp] itself
 *   included: every version and delete it replaces. No stamp in it is newer than [stamp].
 * @property body the record.
 * @property parts the stamp of the edit that last changed each part, by part key, the
 *   [REMAINDER] included: how concurrent versions combine, see [SyncEntry.body].
 * @property sig the base64url signature of the device key of the writer, see
 *   [com.artemchep.keyguard.feature.p2psync.merge.EntrySigner].
 */
@Serializable(with = EntryVersionSerializer::class)
class EntryVersion private constructor(
    raw: JsonObject,
) : RawObject(raw) {
    private val fields: Fields = decodeFields(Fields.serializer(), raw)

    val stamp: Hlc get() = fields.stamp

    val history: VersionVector get() = fields.history

    val body: JsonObject get() = fields.body

    val parts: Map<String, Hlc> get() = fields.parts

    val sig: String get() = fields.sig

    /** The writer, the device that signed the version. */
    val writer: String get() = stamp.node

    /** The size of the [body] as canonical JSON, in UTF-8 bytes. */
    internal val bodySize: Int by lazy(LazyThreadSafetyMode.PUBLICATION) { CanonicalJson.encodeToByteArray(body).size }

    /** Returns this version with the given fields; the others, also unknown ones, stay as they are. */
    fun copy(
        stamp: Hlc = this.stamp,
        history: VersionVector = this.history,
        body: JsonObject = this.body,
        parts: Map<String, Hlc> = this.parts,
        sig: String = this.sig,
    ): EntryVersion {
        val after = Fields(stamp = stamp, history = history, body = body, parts = parts, sig = sig)
        return EntryVersion(raw.withChanges(Fields.serializer(), fields, after))
    }

    override fun toString(): String = "EntryVersion($raw)"

    @Serializable
    private class Fields(
        val stamp: Hlc,
        val history: VersionVector,
        val body: JsonObject,
        val parts: Map<String, Hlc> = emptyMap(),
        val sig: String = "",
    )

    companion object {
        /** The key of the fields outside every part: they change as one value. */
        const val REMAINDER: String = ""

        /** Returns a new version of the given fields. */
        operator fun invoke(
            stamp: Hlc,
            history: VersionVector,
            body: JsonObject,
            parts: Map<String, Hlc> = emptyMap(),
            sig: String = "",
        ): EntryVersion {
            val fields = Fields(stamp = stamp, history = history, body = body, parts = parts, sig = sig)
            return EntryVersion(encodeFields(Fields.serializer(), fields))
        }

        /**
         * Returns the version [raw], as received.
         *
         * @throws IllegalArgumentException if a field this build reads is malformed, or a
         *   number has no canonical form, see [CanonicalJson].
         */
        fun fromRaw(raw: JsonObject): EntryVersion = EntryVersion(raw)
    }
}

internal object EntryVersionSerializer : RawObjectSerializer<EntryVersion>(EntryVersion::fromRaw, EntryVersion::raw)

/**
 * One device's delete of an entry. It holds no part of the record.
 *
 * A delete is kept as its [raw] object, which its signature covers, as a version is, see
 * [EntryVersion.raw].
 *
 * @property stamp when the entry was deleted; its node is the device.
 * @property history the latest stamp of each writer of the entry the device had seen, the
 *   delete itself included: the delete replaces these events and no later ones.
 * @property sig the base64url signature of the device key of the device, see
 *   [com.artemchep.keyguard.feature.p2psync.merge.EntrySigner].
 */
@Serializable(with = EntryDeleteSerializer::class)
class EntryDelete private constructor(
    raw: JsonObject,
) : RawObject(raw) {
    private val fields: Fields = decodeFields(Fields.serializer(), raw)

    val stamp: Hlc get() = fields.stamp

    val history: VersionVector get() = fields.history

    val sig: String get() = fields.sig

    /** The device that deleted the entry. */
    val writer: String get() = stamp.node

    /** Returns this delete with the given fields; the others, also unknown ones, stay as they are. */
    fun copy(
        stamp: Hlc = this.stamp,
        history: VersionVector = this.history,
        sig: String = this.sig,
    ): EntryDelete {
        val after = Fields(stamp = stamp, history = history, sig = sig)
        return EntryDelete(raw.withChanges(Fields.serializer(), fields, after))
    }

    override fun toString(): String = "EntryDelete($raw)"

    @Serializable
    private class Fields(
        val stamp: Hlc,
        val history: VersionVector,
        val sig: String = "",
    )

    companion object {
        /** Returns a new delete of the given fields. */
        operator fun invoke(
            stamp: Hlc,
            history: VersionVector,
            sig: String = "",
        ): EntryDelete {
            val fields = Fields(stamp = stamp, history = history, sig = sig)
            return EntryDelete(encodeFields(Fields.serializer(), fields))
        }

        /**
         * Returns the delete [raw], as received.
         *
         * @throws IllegalArgumentException if a field this build reads is malformed, or a
         *   number has no canonical form, see [CanonicalJson].
         */
        fun fromRaw(raw: JsonObject): EntryDelete = EntryDelete(raw)
    }
}

internal object EntryDeleteSerializer : RawObjectSerializer<EntryDelete>(EntryDelete::fromRaw, EntryDelete::raw)

/**
 * The identity of an entry: its [kind], a wire name, and its [id]. Entries of two kinds are
 * two entries, whatever their ids.
 */
data class EntryKey(
    val kind: String,
    val id: String,
) {
    constructor(kind: EntryKind, id: String) : this(kind.wireName, id)
}

/**
 * The synced state of one entry, a record of one kind: the signed versions and deletes of
 * its devices that nothing replaced yet.
 *
 * A version or a delete replaces every one whose history its own history covers. So an
 * entry usually holds one version; concurrent edits leave a version each, and their parts
 * combine, see [body]. A delete that saw every version leaves nothing of the record but
 * itself. An edit the delete never saw stays whole, and brings the entry back.
 *
 * An entry is known by its [key], its kind and id. A kind this build does not know, of a
 * newer build, or a kind of another scope, is kept and relayed like the others; only the
 * app never reads it.
 *
 * @property id the stable id of the record, a UUID.
 * @property kind the wire name of the kind, see [EntryKind.wireName] and [knownKind].
 * @property versions the versions nothing replaced.
 * @property deletes the deletes nothing replaced.
 */
@Serializable
data class SyncEntry(
    val id: String,
    val kind: String,
    val versions: List<EntryVersion> = emptyList(),
    val deletes: List<EntryDelete> = emptyList(),
) {
    constructor(
        id: String,
        kind: EntryKind,
        versions: List<EntryVersion> = emptyList(),
        deletes: List<EntryDelete> = emptyList(),
    ) : this(id = id, kind = kind.wireName, versions = versions, deletes = deletes)

    /** The identity of the entry: its kind and id. */
    val key: EntryKey get() = EntryKey(kind = kind, id = id)

    /** The canonical JSON bytes and nodes of this entry, as an envelope carries it. */
    internal val usage: EntryUsage by lazy(LazyThreadSafetyMode.PUBLICATION) {
        EntryUsage.of(CanonicalJson.encodeToByteArray(serializer(), this))
    }

    /** The kind, or `null` if this build does not know it. */
    val knownKind: EntryKind? get() = EntryKind.ofWireName(kind)

    /** The newest stamp of any version or delete; [Hlc.MIN] for an entry without any. */
    val stamp: Hlc by lazy(LazyThreadSafetyMode.PUBLICATION) {
        maxOf(versions.maxOfOrNull { it.stamp } ?: Hlc.MIN, deletes.maxOfOrNull { it.stamp } ?: Hlc.MIN)
    }

    /** Everything this entry builds on: the join of the histories of its versions and deletes. */
    val vv: VersionVector by lazy(LazyThreadSafetyMode.PUBLICATION) {
        (versions.map { it.history } + deletes.map { it.history })
            .fold(VersionVector.EMPTY) { history, other -> history.join(other) }
    }

    private val current: SyncEntry by lazy(LazyThreadSafetyMode.PUBLICATION) { reduce() }

    /** `true` if no version is left: a delete replaced every one. */
    val deleted: Boolean get() = current.versions.isEmpty()

    /**
     * The record, or `null` if the entry is [deleted]. Of concurrent versions, each part
     * takes the value of the version that changed it last, see [EntryVersion.parts]: the
     * composition of rules version 1, see [DeviceCaps.rules]. The body of a kind this build
     * does not know is the whole body of one version.
     */
    val body: JsonObject? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val live = current.versions
        if (live.size <= 1) {
            live.singleOrNull()?.body
        } else {
            val schema = knownKind?.schema ?: PartSchema(parts = emptyList())
            val values = schema.parts.associate { spec ->
                val source = current.sourceOf(spec.key)
                spec.key to source?.body?.let { PartAccess.get(it, spec) }
            }
            val remainder = current.sourceOf(EntryVersion.REMAINDER)?.body ?: JsonObject(emptyMap())
            PartAccess.compose(remainder, schema, values)
        }
    }

    /**
     * Returns this entry without what is replaced, in canonical order. Merging is reducing
     * the union, see [com.artemchep.keyguard.feature.p2psync.merge.EntryMerger].
     */
    fun normalized(): SyncEntry = current

    /**
     * Returns the version whose value of the part [key] the [body] shows: the one that
     * changed it last, or `null` if the entry is [deleted].
     */
    fun sourceOf(key: String): EntryVersion? = current.versions
        .maxWithOrNull(compareBy<EntryVersion> { it.parts[key] ?: Hlc.MIN }.then(versionOrder))

    private fun reduce(): SyncEntry {
        val elements = (versions.map(Element::Version) + deletes.map(Element::Delete)).distinct()
        val kept = elements.filter { element -> elements.none { other -> element.isReplacedBy(other) } }
        val reduced = copy(
            versions = kept.filterIsInstance<Element.Version>().map { it.version }.sortedWith(versionOrder),
            deletes = kept.filterIsInstance<Element.Delete>().map { it.delete }.sortedWith(deleteOrder),
        )
        return if (reduced == this) this else reduced
    }
}

/** A version or a delete: what replaces what depends on their histories alone. */
private sealed interface Element {
    val history: VersionVector

    /** Breaks ties between elements with one history, which only a forger makes. */
    val tieBreak: String

    data class Version(val version: EntryVersion) : Element {
        override val history: VersionVector get() = version.history
        override val tieBreak: String get() = "v" + version.canonicalText
    }

    data class Delete(val delete: EntryDelete) : Element {
        override val history: VersionVector get() = delete.history
        override val tieBreak: String get() = "d" + delete.canonicalText
    }

    /**
     * `true` if [other] replaces this element: its history covers this one's, and is larger,
     * or equal with a greater tie break. A partial order, so the reduction never depends on
     * the order of a merge.
     */
    fun isReplacedBy(other: Element): Boolean = other != this &&
        other.history.covers(history) &&
        (history != other.history || other.tieBreak > tieBreak)
}

/** Orders versions: by stamp, then by the canonical text of the version as signed. */
internal val versionOrder: Comparator<EntryVersion> = compareBy<EntryVersion> { it.stamp }.thenBy { it.canonicalText }

/** Orders deletes: by stamp, then by the canonical text of the delete as signed. */
internal val deleteOrder: Comparator<EntryDelete> = compareBy<EntryDelete> { it.stamp }.thenBy { it.canonicalText }

/** Why an incoming entry, or a version or delete of it, was not merged. */
enum class RejectReason {
    /** The id is not a UUID. */
    INVALID_ID,

    /** The entry could not be decoded, or a body has no canonical form, see [CanonicalJson]. */
    MALFORMED,

    /** The envelope holds more than one entry of the kind and id. */
    DUPLICATE_ID,

    /** The entry is empty, or the stamps of a version or delete contradict each other. */
    INCONSISTENT,

    /** A stamp is further in the future than the allowed clock drift. */
    FUTURE_STAMP,

    /** The body of a version is larger than the largest body. */
    OVERSIZED_BODY,

    /**
     * The entry, or a history, has more writers than allowed, or the entry would have more
     * versions and deletes once merged.
     */
    TOO_MANY_WRITERS,

    /** A writer is not a member of the group, or wrote before it joined. */
    UNKNOWN_WRITER,

    /**
     * The entries would outgrow what one envelope carries, see
     * [com.artemchep.keyguard.feature.p2psync.merge.EntryBudget].
     */
    OVER_BUDGET,

    /** The envelope came from a device that is not active. */
    INACTIVE_SENDER,

    /** The signature does not verify with the device key of the writer. */
    FORGED,
}

/** An incoming entry that was dropped, or of which a version or delete was dropped, and why. */
@Serializable
data class RejectedEntry(
    val id: String,
    val reason: RejectReason,
)

/** What an entry takes of an envelope: its canonical JSON [bytes] and [nodes], and a comma. */
internal class EntryUsage(
    val bytes: Int,
    val nodes: Int,
) {
    companion object {
        fun of(json: ByteArray): EntryUsage = EntryUsage(bytes = json.size + 1, nodes = jsonNodes(json) + 1)
    }
}
