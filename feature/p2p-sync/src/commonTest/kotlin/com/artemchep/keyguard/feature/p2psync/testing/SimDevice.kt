package com.artemchep.keyguard.feature.p2psync.testing

import com.artemchep.keyguard.feature.p2psync.clock.HlcClock
import com.artemchep.keyguard.feature.p2psync.merge.EntryBudget
import com.artemchep.keyguard.feature.p2psync.merge.EnvelopeMerger
import com.artemchep.keyguard.feature.p2psync.merge.LocalEditPublisher
import com.artemchep.keyguard.feature.p2psync.merge.MergeResult
import com.artemchep.keyguard.feature.p2psync.merge.Sanitizer
import com.artemchep.keyguard.feature.p2psync.model.EntryKey
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.Envelope
import com.artemchep.keyguard.feature.p2psync.model.LocalEnvelope
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A simulated device: its synced entries, the bodies its local database holds (the last
 * applied snapshot of every live entry of a known kind, by id) and its clock.
 */
class SimDevice(
    val id: String,
    val group: TestGroup,
    private val physicalNow: () -> Long,
) {
    val keys: TestDeviceKeys = group.keys.single { it.id == id }
    val clock = HlcClock(node = id, physicalNow = physicalNow, persist = {})
    val signer = group.entrySigner(keys)
    var roster: Roster = group.roster
    var entries: Map<EntryKey, SyncEntry> = emptyMap()
        private set

    /** The local database: the body of every live entry, as last applied or saved. */
    val view = mutableMapOf<String, JsonObject>()

    /** Every version this device created. */
    val published = mutableListOf<SyncEntry>()

    private val publisher = LocalEditPublisher()
    private val merger = EnvelopeMerger()
    private val validator = group.validator

    fun envelope(): Envelope = Envelope(
        entries = entries.values.sortedBy { it.id },
    )

    /** The [roster] and the [envelope] as the sessions send them. */
    fun localEnvelope(): LocalEnvelope = LocalEnvelope(roster, envelope(), group.crypto::sha256)

    /** The budget of the entries this device holds. */
    fun budget(): EntryBudget = EntryBudget.of(entries.values)

    /** Saves [local] as the new body of the entry [entryId], like an edit in the app. */
    fun edit(entryId: String, local: JsonObject, kind: EntryKind = EntryKind.EMAIL_FORWARDER): SyncEntry {
        val current = entries[EntryKey(kind, entryId)]
        val entry = publisher.publish(entryId, kind, current, view[entryId], local, clock, signer, budget())
        return commit(entry)
    }

    /** Deletes the entry for good. */
    fun delete(entryId: String, kind: EntryKind = EntryKind.EMAIL_FORWARDER): SyncEntry {
        val current = requireNotNull(entries[EntryKey(kind, entryId)]) { "No entry $entryId" }
        return commit(publisher.tombstone(current, clock, signer, budget()))
    }

    /** The entry [entryId] of the [kind], or `null`. */
    fun entry(entryId: String, kind: EntryKind = EntryKind.EMAIL_FORWARDER): SyncEntry? =
        entries[EntryKey(kind, entryId)]

    /** The entry [entryId] of the [kind]; fails if there is none. */
    fun entryOf(entryId: String, kind: EntryKind = EntryKind.EMAIL_FORWARDER): SyncEntry =
        entries.getValue(EntryKey(kind, entryId))

    /**
     * Merges the roster of [from], then the [envelope] it sends, as a session hands them
     * over, and applies the changed entries to the local database.
     */
    fun receive(from: SimDevice, envelope: Envelope = from.envelope()): MergeResult {
        roster = validator.merge(roster, from.roster)
        return receive(envelope, from = from.id)
    }

    /**
     * Merges an [envelope] the device [from] sent, its entries checked against the local
     * roster, and applies the changed entries to the local database.
     */
    fun receive(envelope: Envelope, from: String): MergeResult {
        val sanitizer = Sanitizer(roster, group.groupId, group.crypto, physicalNow())
        val result = merger.merge(entries, envelope, from, sanitizer)
        entries = result.entries
        result.changed.forEach { key -> materialize(entries.getValue(key)) }
        result.newestStamp?.let { clock.receive(it) }
        return result
    }

    /** Hands the envelope of this device to each of the [others]. */
    fun share(vararg others: SimDevice) = others.forEach { it.receive(this) }

    /** The name of the entry [id] as the local database shows it. */
    fun name(id: String): String? = (view[id]?.get("name") as? JsonPrimitive)?.content

    /** Removes the device [device] with the authority key of this one. */
    fun revoke(device: TestDeviceKeys) {
        roster = group.revoke(roster, device, by = keys, at = clock.now())
    }

    private fun commit(entry: SyncEntry): SyncEntry {
        if (entry != entries[entry.key]) published += entry
        entries = entries + (entry.key to entry)
        materialize(entry)
        return entry
    }

    private fun materialize(entry: SyncEntry) {
        // The local database holds no kind of a newer build.
        if (entry.knownKind == null) return
        val body = entry.body
        if (entry.deleted || body == null) view.remove(entry.id) else view[entry.id] = body
    }
}
