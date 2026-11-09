package com.artemchep.keyguard.feature.p2psync.merge

import com.artemchep.keyguard.feature.p2psync.model.EnvelopeCodec
import com.artemchep.keyguard.feature.p2psync.model.RejectReason
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry

/**
 * What the entries of a group may take. One envelope carries them all, so a device never
 * keeps more than it can send: a merge or a local edit that would grow the entries past the
 * budget is refused, see [RejectReason.OVER_BUDGET]. A change that takes no more than the
 * entry it replaces, such as a delete, always fits.
 *
 * Devices merge in different orders, so near the budget they may refuse different entries;
 * none of them stops sending its own.
 */
class EntryBudget private constructor(
    private var bytes: Long,
    private var nodes: Long,
) {
    companion object {
        /** The most canonical JSON bytes of the entries, see [EnvelopeCodec.MAX_CONTENT_SIZE]. */
        const val MAX_BYTES: Long = EnvelopeCodec.MAX_CONTENT_SIZE.toLong()

        /** The most nodes of the entries, see [EnvelopeCodec.MAX_CONTENT_NODES]. */
        const val MAX_NODES: Long = EnvelopeCodec.MAX_CONTENT_NODES.toLong()

        /** The budget of the [entries] a device holds. */
        fun of(entries: Collection<SyncEntry>): EntryBudget = EntryBudget(
            bytes = entries.sumOf { it.usage.bytes.toLong() },
            nodes = entries.sumOf { it.usage.nodes.toLong() },
        )
    }

    /**
     * Counts [after] in place of [before], the entry it replaces, if the entries stay within
     * the budget or [after] takes no more than [before]. Returns `false`, and counts nothing,
     * otherwise.
     */
    fun take(before: SyncEntry?, after: SyncEntry): Boolean {
        val moreBytes = after.usage.bytes - (before?.usage?.bytes ?: 0)
        val moreNodes = after.usage.nodes - (before?.usage?.nodes ?: 0)
        val fits = moreBytes <= 0 && moreNodes <= 0 ||
            bytes + moreBytes <= MAX_BYTES && nodes + moreNodes <= MAX_NODES
        if (fits) {
            bytes += moreBytes
            nodes += moreNodes
        }
        return fits
    }
}
