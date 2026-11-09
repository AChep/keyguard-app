package com.artemchep.keyguard.feature.p2psync.merge

import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.clock.HlcClock
import com.artemchep.keyguard.feature.p2psync.clock.VersionVector
import com.artemchep.keyguard.feature.p2psync.model.CanonicalJson
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.EntryVersion
import com.artemchep.keyguard.feature.p2psync.model.RejectReason
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.parts.PartAccess
import com.artemchep.keyguard.feature.p2psync.parts.PartSchema
import kotlinx.serialization.json.JsonObject

/**
 * A local edit was not published: its version or delete breaks a check every peer runs,
 * see [Sanitizer], such as a body larger than the largest one. The entry stays as it was.
 *
 * @property reason the check the edit breaks.
 */
class EditRefusedException(
    val reason: RejectReason,
) : IllegalStateException("The edit was not published: $reason")

/**
 * Turns local edits into new versions of entries, signed by this device.
 *
 * A local edit is the difference between the body last applied to the local database
 * (`applied`) and the body the user saved (`local`). The new version takes the changed
 * parts from `local` and the rest from the current entry, so parts that arrived from other
 * devices since `applied` was taken are never overwritten. It builds on everything the
 * entry holds, so it replaces every version and delete there is.
 *
 * A version or delete that a peer would drop is never published: it gets the checks of the
 * [Sanitizer] that need no roster first, and fails with [EditRefusedException].
 */
class LocalEditPublisher {
    /**
     * Publishes a local edit of the entry [id].
     *
     * @param current the current synced version, or `null` for a new entry.
     * @param applied the body that the local database was last given, or `null` if it never
     *   had one.
     * @param local the body as saved by the user.
     * @param clock the clock of this device.
     * @param signer signs the version with the device key of this device.
     * @param budget the budget of the entries this device holds, which counts the new
     *   version in place of [current].
     * @return the new version, or [current] itself if nothing changed.
     * @throws EditRefusedException if a peer would drop the new version: its body has no
     *   canonical form, or breaks a limit of the [Sanitizer]; or if the entries would
     *   outgrow the [budget].
     */
    // The edit, and who makes it.
    @Suppress("LongParameterList")
    fun publish(
        id: String,
        kind: EntryKind,
        current: SyncEntry?,
        applied: JsonObject?,
        local: JsonObject,
        clock: HlcClock,
        signer: EntrySigner,
        budget: EntryBudget,
    ): SyncEntry {
        if (!CanonicalJson.isCanonical(local)) throw EditRefusedException(RejectReason.MALFORMED)
        val schema = kind.schema
        val changes = changedKeys(schema, applied, local)
        // A new or deleted entry is saved whole.
        val live = current?.takeUnless { it.deleted }
        if (live != null && changes.isEmpty()) return live

        val stamp = current?.let { clock.nowAfter(it.stamp) } ?: clock.now()
        val keys = schema.parts.map { it.key } + EntryVersion.REMAINDER
        val changed = if (live == null) keys.toSet() else changes
        val version = signer.version(
            entryId = id,
            kind = kind,
            stamp = stamp,
            history = (current?.vv ?: VersionVector.EMPTY).witness(stamp),
            body = bodyOf(schema, live, local, changed),
            parts = keys.associateWith { key -> partStamp(live, key, changed, stamp) },
        )
        versionProblem(version, clock.physicalNow())?.let { throw EditRefusedException(it) }
        val edit = SyncEntry(id = id, kind = kind, versions = listOf(version))
        val next = current
            ?.let { EntryMerger.merge(it, edit) }
            ?: edit.normalized()
        return budget.takeOrRefuse(current, next)
    }

    /**
     * Deletes the entry: the delete saw everything the entry holds, so nothing of the record
     * stays. An edit the delete never saw brings the entry back whole. Returns [current] if
     * it is already deleted.
     *
     * @throws EditRefusedException if a peer would drop the delete, such as for a history of
     *   more writers than the [Sanitizer] admits, or if the entries would outgrow the
     *   [budget], see [publish].
     */
    fun tombstone(
        current: SyncEntry,
        clock: HlcClock,
        signer: EntrySigner,
        budget: EntryBudget,
    ): SyncEntry = if (current.deleted) {
        current
    } else {
        val stamp = clock.nowAfter(current.stamp)
        val delete = signer.delete(current.id, current.kind, stamp, history = current.vv.witness(stamp))
        deleteProblem(delete, clock.physicalNow())?.let { throw EditRefusedException(it) }
        val next = EntryMerger.merge(current, current.copy(versions = emptyList(), deletes = listOf(delete)))
        budget.takeOrRefuse(current, next)
    }

    /** The changed parts from [local], the others as the [live] entry shows them. */
    private fun bodyOf(
        schema: PartSchema,
        live: SyncEntry?,
        local: JsonObject,
        changed: Set<String>,
    ): JsonObject {
        val current = live?.body
            ?: return local
        val values = schema.parts.associate { spec ->
            val source = if (spec.key in changed) local else current
            spec.key to PartAccess.get(source, spec)
        }
        val remainder = if (EntryVersion.REMAINDER in changed) local else current
        return PartAccess.compose(remainder, schema, values)
    }

    /** The stamp of the edit that last changed the part [key], [stamp] if this one does. */
    private fun partStamp(
        live: SyncEntry?,
        key: String,
        changed: Set<String>,
        stamp: Hlc,
    ): Hlc = if (key in changed) {
        stamp
    } else {
        live?.sourceOf(key)?.parts?.get(key) ?: stamp
    }
}

/** Counts [after] in place of [before] and returns it, or refuses it if the entries would outgrow the budget. */
private fun EntryBudget.takeOrRefuse(before: SyncEntry?, after: SyncEntry): SyncEntry {
    if (!take(before, after)) throw EditRefusedException(RejectReason.OVER_BUDGET)
    return after
}

/**
 * The keys of the parts that differ between the [applied] and the [local] body, with
 * [EntryVersion.REMAINDER] if the remainder differs.
 */
private fun changedKeys(schema: PartSchema, applied: JsonObject?, local: JsonObject): Set<String> {
    val parts = schema.parts
        .filter { spec ->
            val before = applied?.let { PartAccess.get(it, spec) }
            val after = PartAccess.get(local, spec)
            before != after
        }
        .mapTo(mutableSetOf()) { it.key }
    val remainderBefore = applied?.let { PartAccess.remainder(it, schema) } ?: JsonObject(emptyMap())
    if (remainderBefore != PartAccess.remainder(local, schema)) parts += EntryVersion.REMAINDER
    return parts
}
