package com.artemchep.keyguard.feature.p2psync.model

import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.parts.KeyguardPartSchemas
import com.artemchep.keyguard.feature.p2psync.roster.RosterActivity
import com.artemchep.keyguard.feature.p2psync.roster.isStrictlySorted
import com.artemchep.keyguard.feature.p2psync.string
import com.artemchep.keyguard.feature.p2psync.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The object key of a group: the key of the blobs the group keeps on the relay. It travels
 * as the entry [ENTRY_KEY], with the body `{"key": base64url, "covers": [device id, …]}`.
 *
 * Entries reach active devices only, so a device that is removed never learns a key
 * written after its removal. A key written by a device that is no longer active counts as
 * no key, and so does a key that leaves a removed device uncovered, see [usable]. A device
 * then writes a new key, see [com.artemchep.keyguard.feature.p2psync.merge.ObjectKeyRotation].
 *
 * @property key the [KEY_BYTES] bytes of the key.
 * @property covers the ids of the devices that were not active in the roster of the writer
 *   when it wrote the key, strictly sorted: revoked, or evicted by a seal. At most
 *   [Roster.MAX_DEVICES] of them.
 */
class ObjectKey(
    val key: ByteArray,
    val covers: List<String>,
) {
    init {
        require(key.size == KEY_BYTES) { "An object key has $KEY_BYTES bytes" }
        require(covers.size <= Roster.MAX_DEVICES && covers.isStrictlySorted()) {
            "An object key covers at most ${Roster.MAX_DEVICES} devices, strictly sorted"
        }
    }

    /** `true` if the key covers every device of [inactive]. */
    fun coversAll(inactive: Collection<String>): Boolean = covers.containsAll(inactive)

    /** The id of the key: the lowercase hex of the first 8 bytes of its SHA-256, by [hasher]. */
    fun keyId(hasher: (ByteArray) -> ByteArray): String = P2pEncoding.hex(hasher(key).copyOf(KEY_ID_BYTES))

    /** The body of the entry of this key. */
    fun toBody(): JsonObject = JsonObject(
        mapOf(
            FIELD_KEY to JsonPrimitive(P2pEncoding.base64Url(key)),
            FIELD_COVERS to JsonArray(covers.map(::JsonPrimitive)),
        ),
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is ObjectKey && key.contentEquals(other.key) && covers == other.covers

    override fun hashCode(): Int = 31 * key.contentHashCode() + covers.hashCode()

    override fun toString(): String = "ObjectKey(covers=$covers)"

    companion object {
        /** The id of the entry of the object key, the same in every group: entries are signed with their group. */
        const val ENTRY_ID: String = "00000000-0000-8000-8000-000000000001"

        /** The entry of the object key. */
        val ENTRY_KEY: EntryKey = EntryKey(EntryKind.OBJECT_KEY, ENTRY_ID)

        /** The bytes of a key. */
        const val KEY_BYTES: Int = 32

        private const val KEY_ID_BYTES: Int = 8

        private const val FIELD_KEY: String = "key"
        private const val FIELD_COVERS: String = "covers"

        /**
         * Returns the key the [entry] holds, or `null` if there is none: the entry is not
         * [ENTRY_KEY], is deleted, or its body is not a valid key, see [fromBody].
         */
        fun fromEntry(entry: SyncEntry?): ObjectKey? = entry
            ?.takeIf { it.key == ENTRY_KEY }
            ?.body
            ?.let(::fromBody)

        /**
         * Returns the key the [entry] holds, see [fromEntry], if its writer is active in the
         * [activity], and it covers every inactive device of the [activity]. The writer is the
         * device that signed the version the key comes from. A key of a device that is no
         * longer active counts as no key, whatever it covers: the device may have listed
         * itself in [covers] before its removal. A key that leaves a removed device uncovered
         * counts as no key too, also while no new key fits: the removed device knows it.
         */
        fun usable(entry: SyncEntry?, activity: RosterActivity): ObjectKey? {
            val writer = entry?.sourceOf(KeyguardPartSchemas.OBJECT_KEY_PART)?.writer
            return fromEntry(entry)?.takeIf { key ->
                writer != null && activity.isActive(writer) && key.coversAll(activity.inactiveIds)
            }
        }

        /**
         * Returns the key of the [body], or `null` if it is not a valid one: the key must be
         * the canonical base64url of [KEY_BYTES] bytes, and the covered devices a strictly
         * sorted list of at most [Roster.MAX_DEVICES] strings. Other fields are ignored.
         */
        fun fromBody(body: JsonObject): ObjectKey? {
            val key = body.string(FIELD_KEY)
                ?.let(P2pEncoding::canonicalBase64UrlOrNull)
                ?.takeIf { it.size == KEY_BYTES }
            val covers = (body[FIELD_COVERS] as? JsonArray)
                ?.takeIf { it.size <= Roster.MAX_DEVICES }
                ?.map { it.stringOrNull() }
                ?.takeIf { ids -> ids.none { it == null } }
                ?.filterNotNull()
                ?.takeIf { it.isStrictlySorted() }
            return if (key != null && covers != null) ObjectKey(key, covers) else null
        }
    }
}
