import Foundation
import Observation

/// Main-actor row cache separating list structure from per-row content updates.
@MainActor
final class VaultRowStore {
    @MainActor
    @Observable
    final class RowBox {
        var row: VaultRow?
        var decoration: VaultRowDecoration?
    }

    /// The list's order / membership, separate from row content so the two
    /// invalidate independently (see the class KDoc).
    @MainActor
    @Observable
    final class Structure {
        /// The complete render order. The list `ForEach`es over this.
        var entries: [VaultRowEntry] = []
        /// The revision of the frame currently rendered; `reportScroll` must
        /// echo it so the source can ignore reports against stale frames.
        var revision: Int64 = 0
        /// The number of cipher rows of the main list (sections excluded).
        var itemCount = 0
        /// The row to keep anchored on structure changes; `nil` = none.
        var scrollAnchor: (id: String, offset: Int)?
    }

    /// Row content keyed by row id. Not observable by design — see the class KDoc.
    private(set) var boxes: [String: RowBox] = [:]
    let structure = Structure()

    private(set) var generation = 0

    private static let backfillThreshold = 500
    /// Upserts applied per turn — the synchronous first chunk and each
    /// back-fill tick alike.
    private static let backfillChunkSize = 250

    private var pendingBackfill: [VaultRow] = []
    private var backfillTask: Task<Void, Never>?

    // MARK: - Reads

    func box(for id: String) -> RowBox {
        if let box = boxes[id] { return box }
        vaultLog("box(for:) MISS for row '\(id)' — creating a placeholder; the structure/content contract was violated")
        let box = RowBox()
        boxes[id] = box
        return box
    }

    // MARK: - Apply

    @discardableResult
    func apply(_ delta: VaultDelta, generation: Int) -> Bool {
        guard generation == self.generation else {
            vaultLog(
                "dropping delta rev \(delta.revision) — stale generation \(generation) (store is at \(self.generation))"
            )
            return false
        }

        if delta.isReset {
            // The vault locked: drop ALL cached state and re-baseline. Bumping
            // the generation also invalidates any in-flight conversions from
            // before the reset frame (the caller re-captures from us).
            reset()
            return true
        }

        backfillTask?.cancel()
        backfillTask = nil
        var upserts = delta.upserts
        if !pendingBackfill.isEmpty {
            let incomingIds = Set(delta.upserts.map(\.id))
            let removedIds = Set(delta.removedIds)
            let carried = pendingBackfill.filter {
                !incomingIds.contains($0.id) && !removedIds.contains($0.id)
            }
            upserts = carried + upserts
            pendingBackfill = []
        }

        if !delta.isFull {
            vaultLog(
                "delta rev \(delta.revision) is NOT a full frame (ops: \(delta.hasOps)) — "
                    + "Phase-3 ops are unsupported; ignoring structure, applying content only"
            )
        } else if delta.hasOps {
            vaultLog("full delta rev \(delta.revision) unexpectedly carries ops — ignoring them")
        }

        // 1. Removals: drop the content cache entries.
        for id in delta.removedIds {
            boxes.removeValue(forKey: id)
        }

        let orderUnchanged = delta.entries == structure.entries
        if delta.isFull, orderUnchanged, upserts.count > Self.backfillThreshold {
            applyUpserts(upserts.prefix(Self.backfillChunkSize))
            pendingBackfill = Array(upserts.dropFirst(Self.backfillChunkSize))
            scheduleBackfill()
        } else {
            applyUpserts(upserts)
        }

        if delta.isFull {
            let idSet = Set(delta.entries.map(\.id))
            var missing: [String] = []
            for entry in delta.entries where boxes[entry.id] == nil {
                boxes[entry.id] = RowBox()
                missing.append(entry.id)
            }
            if !missing.isEmpty {
                vaultLog(
                    "delta rev \(delta.revision): \(missing.count) structure id(s) arrived with no "
                        + "content upsert — placeholder boxes created (first: '\(missing[0])')"
                )
            }
            if boxes.count > idSet.count {
                boxes = boxes.filter { idSet.contains($0.key) }
            }
            // Back-fill rows for pruned ids would recreate zombie boxes; drop them.
            if !pendingBackfill.isEmpty {
                pendingBackfill.removeAll { !idSet.contains($0.id) }
            }
            if structure.entries != delta.entries {
                structure.entries = delta.entries
            }
            structure.revision = delta.revision
            if structure.itemCount != delta.itemCount {
                structure.itemCount = delta.itemCount
            }
            let anchor = delta.scrollAnchorId.map { (id: $0, offset: delta.scrollAnchorOffset) }
            if structure.scrollAnchor?.id != anchor?.id || structure.scrollAnchor?.offset != anchor?.offset {
                structure.scrollAnchor = anchor
            }
        }

        // 4. Decorations: reset replaces the whole map (clear everything the
        //    upserts don't re-carry), then upserts, then removals. All writes
        //    equality-guarded — decorations have no rev fingerprint.
        if delta.decorationsReset {
            let upsertIds = Set(delta.decorationUpserts.map(\.id))
            for (id, box) in boxes where box.decoration != nil && !upsertIds.contains(id) {
                box.decoration = nil
            }
        }
        for decoration in delta.decorationUpserts {
            guard let box = boxes[decoration.id] else {
                // A decoration for a row we hold no content box for (e.g. the
                // row was pruned this same frame) — nothing to attach it to.
                continue
            }
            if box.decoration != decoration {
                box.decoration = decoration
            }
        }
        for id in delta.decorationRemovedIds {
            if let box = boxes[id], box.decoration != nil {
                box.decoration = nil
            }
        }
        return true
    }

    /// Drops ALL cached state and bumps the generation, cutting off any deltas
    /// still in flight from before the cut. Called by the session model's
    /// `stop()` (and by `apply` for an in-band `isReset` frame).
    func reset() {
        backfillTask?.cancel()
        backfillTask = nil
        pendingBackfill = []
        generation += 1
        boxes = [:]
        if !structure.entries.isEmpty {
            structure.entries = []
        }
        // Re-baseline: revisions are monotonic within one unlock session only.
        structure.revision = 0
        if structure.itemCount != 0 {
            structure.itemCount = 0
        }
        if structure.scrollAnchor != nil {
            structure.scrollAnchor = nil
        }
    }

    // MARK: - Internals

    private func applyUpserts(_ rows: some Sequence<VaultRow>) {
        for row in rows {
            if let box = boxes[row.id] {
                // The rev skip: identical fingerprint = identical rendered
                // content; writing it anyway would invalidate the cell.
                if box.row?.rev == row.rev { continue }
                box.row = row
            } else {
                let box = RowBox()
                box.row = row
                boxes[row.id] = box
            }
        }
    }

    private func scheduleBackfill() {
        let expectedGeneration = generation
        backfillTask = Task { @MainActor [weak self] in
            while true {
                await Task.yield()
                guard let self, !Task.isCancelled, self.generation == expectedGeneration else { return }
                if self.pendingBackfill.isEmpty { return }
                let chunk = Array(self.pendingBackfill.prefix(Self.backfillChunkSize))
                self.pendingBackfill.removeFirst(chunk.count)
                self.applyUpserts(chunk)
            }
        }
    }
}
