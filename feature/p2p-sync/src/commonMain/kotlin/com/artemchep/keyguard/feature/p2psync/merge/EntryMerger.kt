package com.artemchep.keyguard.feature.p2psync.merge

import com.artemchep.keyguard.feature.p2psync.model.SyncEntry

/**
 * Merges two versions of one entry, of one kind and id: the union of their versions and
 * deletes, without the ones another replaces, see [SyncEntry.normalized]. The merge is
 * commutative, associative and idempotent, so every device that merges the same versions,
 * in any order, gets the identical entry.
 *
 * What the merged entry holds follows from what is left, see [SyncEntry]: a delete that saw
 * every version leaves nothing of the record, and a version the delete never saw stays
 * whole.
 */
object EntryMerger {
    /** Returns the merge of [a] and [b], two versions of the same entry. */
    fun merge(a: SyncEntry, b: SyncEntry): SyncEntry {
        require(a.key == b.key) { "Can not merge different entries ${a.key} and ${b.key}" }
        if (a == b) return a
        val merged = a.copy(versions = a.versions + b.versions, deletes = a.deletes + b.deletes).normalized()
        // A merge that adds nothing keeps the entry, with what it has computed already.
        return if (merged == a) a else merged
    }
}
