package com.artemchep.keyguard.apple.vault

import com.artemchep.keyguard.feature.home.vault.apple.AppleVaultListState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/**
 * Every surface emitting [AppleVaultListState] reuses this exact publishing contract:
 * - full frames diffed against the last DELIVERED state ([diffFullFrame]); a
 *   no-change diff publishes nothing;
 * - a single monotonic-revision reset frame on lock ([resetDelta]);
 * - per-observer [run] state (last delivered frame + revision), so ONE
 *   publisher instance safely serves multiple concurrent observers.
 *
 * Threading & coalescing: [run] performs no dispatch of its own — the caller
 * drives it from its background observer scope (so `onChange` fires off-main by
 * design, the contract the Swift FIFO delta pump depends on) and applies the
 * ~48ms burst coalescing (`throttleLatest`) to the non-null runs of [states]
 * BEFORE they reach [run]. The `null` lock marker is delivered un-throttled so a
 * reset is never coalesced away.
 */
internal class VaultListFramePublisher {

    /** A `null` element means the source locked / tore down. */
    suspend fun run(
        states: Flow<AppleVaultListState?>,
        onChange: (VaultListDelta) -> Unit,
    ) {
        // Both anchored to DELIVERED frames only: `lastDelivered` advances after
        // `onChange` returns, `lastRevision` tracks the highest revision handed
        // out so the lock-reset stays monotonic.
        var lastDelivered: AppleVaultListState? = null
        var lastRevision = 0L
        states.collect { state ->
            if (state == null) {
                // Locked (or not yet unlocked). A reset before the first frame
                // would be pure noise.
                if (lastDelivered != null) {
                    lastDelivered = null
                    lastRevision += 1
                    onChange(resetDelta(revision = lastRevision))
                }
                return@collect
            }
            val delta = diffFullFrame(state, lastDelivered)
                ?: return@collect
            onChange(delta)
            lastDelivered = state
            lastRevision = state.revision
        }
    }

    /**
     * `null` when nothing changed. A row is carried when it is new to the client or its `rev` changed
     * (sections fold their title into it, marker rows are constant); decorations have no fingerprint,
     * so they are compared structurally.
     */
    private fun diffFullFrame(
        state: AppleVaultListState,
        last: AppleVaultListState?,
    ): VaultListDelta? {
        val lastRows = last?.rows.orEmpty()
        val upserts = ArrayList<VaultRowSnapshot>()
        for (row in state.rows.values) {
            val old = lastRows[row.id]
            if (old == null || old.rev != row.rev) {
                upserts += row.toSnapshot()
            }
        }
        val removedIds = lastRows.keys.filter { it !in state.rows }

        val lastDecorations = last?.decorations.orEmpty()
        val decorationUpserts = state.decorations.values
            .filter { decoration -> lastDecorations[decoration.id] != decoration }
            .map { it.toSnapshot() }
        val decorationRemovedIds = lastDecorations.keys.filter { it !in state.decorations }

        val rowsUnchanged = upserts.isEmpty() && removedIds.isEmpty()
        val decorationsUnchanged = decorationUpserts.isEmpty() && decorationRemovedIds.isEmpty()
        val contentUnchanged = rowsUnchanged && decorationsUnchanged
        if (last != null && contentUnchanged && state.hasSameLayout(last)) {
            return null
        }
        return VaultListDelta(
            revision = state.revision,
            baseRevision = -1L,
            isFull = true,
            isReset = false,
            fullEntryIds = state.entries.map { it.id },
            fullEntryKinds = state.entries.map { it.kind },
            ops = emptyList(),
            upserts = upserts,
            removedIds = removedIds,
            decorationUpserts = decorationUpserts,
            decorationRemovedIds = decorationRemovedIds,
            decorationsReset = false,
            itemCount = state.itemCount,
            scrollAnchorId = state.scrollAnchorId,
            scrollAnchorOffset = state.scrollAnchorOffset,
        )
    }

    private fun AppleVaultListState.hasSameLayout(other: AppleVaultListState): Boolean =
        entries == other.entries &&
            itemCount == other.itemCount &&
            scrollAnchorId == other.scrollAnchorId &&
            scrollAnchorOffset == other.scrollAnchorOffset

    private fun resetDelta(revision: Long): VaultListDelta = VaultListDelta(
        revision = revision,
        baseRevision = -1L,
        isFull = true,
        isReset = true,
        fullEntryIds = emptyList(),
        fullEntryKinds = emptyList(),
        ops = emptyList(),
        upserts = emptyList(),
        removedIds = emptyList(),
        decorationUpserts = emptyList(),
        decorationRemovedIds = emptyList(),
        decorationsReset = true,
        itemCount = 0,
        scrollAnchorId = "",
        scrollAnchorOffset = 0,
    )
}
