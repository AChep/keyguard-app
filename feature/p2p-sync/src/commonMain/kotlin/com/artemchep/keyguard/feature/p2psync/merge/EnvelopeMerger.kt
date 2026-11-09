package com.artemchep.keyguard.feature.p2psync.merge

import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.model.EntryKey
import com.artemchep.keyguard.feature.p2psync.model.Envelope
import com.artemchep.keyguard.feature.p2psync.model.RejectReason
import com.artemchep.keyguard.feature.p2psync.model.RejectedEntry
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry

/**
 * The outcome of merging an envelope.
 *
 * @property entries every local entry by kind and id, with the incoming ones merged in.
 * @property changed the kinds and ids of the entries that differ from the local ones.
 * @property rejected the incoming entries of which something was dropped: by the sanitizer,
 *   or whole, as the merge would have outgrown the limits.
 * @property accepted the incoming entries that were merged, without what the sanitizer dropped.
 * @property validFrom the local physical time from which an incoming version or delete
 *   dropped as [RejectReason.FUTURE_STAMP] would be taken, see [SanitizedEntries.validFrom];
 *   `null` if none was dropped so.
 */
data class MergeResult(
    val entries: Map<EntryKey, SyncEntry>,
    val changed: Set<EntryKey>,
    val rejected: List<RejectedEntry>,
    val accepted: List<SyncEntry>,
    val validFrom: Long? = null,
) {
    /** The newest stamp of the accepted entries, which the local clock must witness; `null` if none. */
    val newestStamp: Hlc? get() = accepted.maxOfOrNull { it.stamp }
}

/** Merges the entries of a received envelope into the local ones. */
class EnvelopeMerger {
    /**
     * Sanitizes the entries of [incoming], which the device [from] sent, with [sanitizer] and
     * merges the accepted ones into [local], the local entries by kind and id.
     *
     * Each version and delete passes the sanitizer alone, but their union may not: an entry
     * that would hold more versions and deletes than the limits admit keeps its local state,
     * so this device never keeps an entry its peers refuse. Nor do the entries outgrow what
     * one envelope carries, see [EntryBudget]: an entry that would keeps its local state.
     */
    fun merge(
        local: Map<EntryKey, SyncEntry>,
        incoming: Envelope,
        from: String,
        sanitizer: Sanitizer,
    ): MergeResult {
        val sanitized = sanitizer.sanitize(incoming, from = from, known = local)
        val entries = local.toMutableMap()
        val accepted = mutableListOf<SyncEntry>()
        val rejected = sanitized.rejected.toMutableList()
        val budget = EntryBudget.of(local.values)
        sanitized.accepted.forEach { entry ->
            val existing = entries[entry.key]
            val merged = existing?.let { EntryMerger.merge(it, entry) } ?: entry
            when {
                !SanitizerLimits.admits(merged) -> rejected += RejectedEntry(entry.id, RejectReason.TOO_MANY_WRITERS)
                !budget.take(existing, merged) -> rejected += RejectedEntry(entry.id, RejectReason.OVER_BUDGET)
                else -> {
                    entries[entry.key] = merged
                    accepted += entry
                }
            }
        }
        val changed = accepted
            .map { it.key }
            .filter { entries[it] != local[it] }
            .toSet()
        return MergeResult(entries, changed, rejected, accepted, sanitized.validFrom)
    }
}
