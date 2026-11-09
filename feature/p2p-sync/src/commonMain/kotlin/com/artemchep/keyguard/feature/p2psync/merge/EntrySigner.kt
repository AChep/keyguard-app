package com.artemchep.keyguard.feature.p2psync.merge

import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.clock.VersionVector
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.crypto.signToBase64Url
import com.artemchep.keyguard.feature.p2psync.crypto.signedBytes
import com.artemchep.keyguard.feature.p2psync.model.EntryDelete
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.EntryVersion
import kotlinx.serialization.json.JsonObject

/**
 * Signs the versions and deletes of one device with its device key, for the entries of the
 * sync group [groupId].
 *
 * A signature covers the version or delete as it is sent, every field but its own `sig`,
 * also the ones a newer build adds, with the group, the entry and its kind as context, see
 * [EntrySignatures]. So it can not be moved to another entry or group. A device can only
 * write as itself: the stamp names the writer, whose key must sign it.
 */
class EntrySigner(
    private val crypto: P2pCrypto,
    private val groupId: String,
    private val signPrivateKey: ByteArray,
) {
    /** Returns the version of the entry [entryId] of the [kind], a wire name, saved at [stamp]. */
    // The version, and the entry it belongs to.
    @Suppress("LongParameterList")
    fun version(
        entryId: String,
        kind: String,
        stamp: Hlc,
        history: VersionVector,
        body: JsonObject,
        parts: Map<String, Hlc>,
    ): EntryVersion {
        val version = EntryVersion(stamp = stamp, history = history, body = body, parts = parts)
        return version.copy(sig = sign(EntrySignatures.versionBytes(groupId, entryId, kind, version)))
    }

    /** Returns the version of the entry [entryId] of the [kind] saved at [stamp]. */
    // The version, and the entry it belongs to.
    @Suppress("LongParameterList")
    fun version(
        entryId: String,
        kind: EntryKind,
        stamp: Hlc,
        history: VersionVector,
        body: JsonObject,
        parts: Map<String, Hlc>,
    ): EntryVersion = version(entryId, kind.wireName, stamp, history, body, parts)

    /**
     * Returns the delete of the entry [entryId] of the [kind], a wire name, at [stamp],
     * which saw the events [history].
     */
    fun delete(
        entryId: String,
        kind: String,
        stamp: Hlc,
        history: VersionVector,
    ): EntryDelete {
        val delete = EntryDelete(stamp = stamp, history = history)
        return delete.copy(sig = sign(EntrySignatures.deleteBytes(groupId, entryId, kind, delete)))
    }

    private fun sign(bytes: ByteArray): String = crypto.signToBase64Url(signPrivateKey, bytes)
}

/**
 * The exact bytes the signatures of versions and deletes cover: the domain, and the
 * canonical JSON of the object as sent without its `sig`, with the group, the entry id and
 * the kind as context, see [signedBytes].
 */
internal object EntrySignatures {
    private const val VERSION_DOMAIN = "keyguard-entry-version/v1"
    private const val DELETE_DOMAIN = "keyguard-entry-delete/v1"
    private const val FIELD_SIG = "sig"

    fun versionBytes(
        groupId: String,
        entryId: String,
        kind: String,
        version: EntryVersion,
    ): ByteArray = signedBytes(VERSION_DOMAIN, context(groupId, entryId, kind), JsonObject(version.raw - FIELD_SIG))

    fun deleteBytes(
        groupId: String,
        entryId: String,
        kind: String,
        delete: EntryDelete,
    ): ByteArray = signedBytes(DELETE_DOMAIN, context(groupId, entryId, kind), JsonObject(delete.raw - FIELD_SIG))

    /** Where a version or delete belongs: the group, the entry and its kind. */
    private fun context(
        groupId: String,
        entryId: String,
        kind: String,
    ): Map<String, String> = mapOf(
        "group" to groupId,
        "entry" to entryId,
        "kind" to kind,
    )
}
