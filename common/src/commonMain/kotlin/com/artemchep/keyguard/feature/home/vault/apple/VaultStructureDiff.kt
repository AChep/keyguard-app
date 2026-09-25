package com.artemchep.keyguard.feature.home.vault.apple

/** Structure operations are disabled by default; enable them only with a compatible client. */
internal const val STRUCTURE_OPS_DEFAULT = false

internal data class StructureDiffResult(
    val isFull: Boolean,
    /** Empty when [isFull]; pre-sorted per the application-order contract. */
    val ops: List<AppleVaultStructureOp>,
) {
    companion object {
        val FULL = StructureDiffResult(
            isFull = true,
            ops = emptyList(),
        )

        val EMPTY = StructureDiffResult(
            isFull = false,
            ops = emptyList(),
        )
    }
}

internal fun diffStructure(
    old: List<AppleVaultEntry>,
    new: List<AppleVaultEntry>,
    opsEnabled: Boolean = STRUCTURE_OPS_DEFAULT,
    churnThreshold: Double = 0.3,
): StructureDiffResult {
    if (!opsEnabled) {
        return StructureDiffResult.FULL
    }

    val oldIndexById = HashMap<String, Int>(old.size * 2)
    old.forEachIndexed { index, entry ->
        val previous = oldIndexById.put(entry.id, index)
        if (previous != null) {
            // Duplicate ids break keyed diffing; be defensive.
            return StructureDiffResult.FULL
        }
    }
    val newIndexById = HashMap<String, Int>(new.size * 2)
    new.forEachIndexed { index, entry ->
        val previous = newIndexById.put(entry.id, index)
        if (previous != null) {
            return StructureDiffResult.FULL
        }
        // A surviving id must keep its kind; a kind change means the id got
        // reused for a different thing â transfer the structure whole.
        val oldIndex = oldIndexById[entry.id]
        if (oldIndex != null && old[oldIndex].kind != entry.kind) {
            return StructureDiffResult.FULL
        }
    }

    // Fast path: identical structure.
    if (old.size == new.size) {
        var identical = true
        for (i in old.indices) {
            if (old[i].id != new[i].id) {
                identical = false
                break
            }
        }
        if (identical) {
            return StructureDiffResult.EMPTY
        }
    }

    // 1. Removals, descending by old index.
    val removalOps = mutableListOf<AppleVaultStructureOp>()
    for (i in old.indices.reversed()) {
        val entry = old[i]
        if (entry.id !in newIndexById) {
            removalOps += AppleVaultStructureOp(
                kind = AppleVaultStructureOp.KIND_REMOVE,
                index = i,
                fromIndex = -1,
                id = entry.id,
            )
        }
    }

    // 2. Stayers: the longest increasing subsequence of the survivors'
    // old-relative positions, sequenced in new order. Everything off the
    // LIS moves; everything on it is never mentioned in the ops.
    val survivorPosById = HashMap<String, Int>(old.size * 2)
    for (entry in old) {
        if (entry.id in newIndexById) {
            survivorPosById[entry.id] = survivorPosById.size
        }
    }
    val survivorIdsInNewOrder = ArrayList<String>(survivorPosById.size)
    for (entry in new) {
        if (entry.id in oldIndexById) {
            survivorIdsInNewOrder += entry.id
        }
    }
    val positionSeq = IntArray(survivorIdsInNewOrder.size) { i ->
        survivorPosById.getValue(survivorIdsInNewOrder[i])
    }
    val stayIds = HashSet<String>()
    for (seqIndex in longestIncreasingSubsequence(positionSeq)) {
        stayIds += survivorIdsInNewOrder[seqIndex]
    }

    // 3. Inserts and moves, ascending by target index. A MOVE carries its
    // OLD-list position as fromIndex â it is extracted alongside the
    // removals and re-inserted here, per the two-phase contract.
    val placementOps = mutableListOf<AppleVaultStructureOp>()
    for (targetIndex in new.indices) {
        val id = new[targetIndex].id
        when {
            id !in oldIndexById -> {
                placementOps += AppleVaultStructureOp(
                    kind = AppleVaultStructureOp.KIND_INSERT,
                    index = targetIndex,
                    fromIndex = -1,
                    id = id,
                )
            }

            id !in stayIds -> {
                placementOps += AppleVaultStructureOp(
                    kind = AppleVaultStructureOp.KIND_MOVE,
                    index = targetIndex,
                    fromIndex = oldIndexById.getValue(id),
                    id = id,
                )
            }
        }
    }

    // 4. Op budget: a heavily churned list is cheaper to transfer whole.
    val totalOps = removalOps.size + placementOps.size
    if (totalOps > new.size * churnThreshold) {
        return StructureDiffResult.FULL
    }

    // 5. Defensive: replay the documented two-phase application; if it ever
    // fails to reproduce the target, a full snapshot is always correct.
    val working = ArrayList<String>(old.size)
    for (entry in old) {
        working += entry.id
    }
    val extractionIndices = ArrayList<Int>(totalOps)
    removalOps.mapTo(extractionIndices) { it.index }
    for (op in placementOps) {
        if (op.kind == AppleVaultStructureOp.KIND_MOVE) {
            extractionIndices += op.fromIndex
        }
    }
    extractionIndices.sortDescending()
    for (index in extractionIndices) {
        working.removeAt(index)
    }
    for (op in placementOps) {
        working.add(op.index, op.id)
    }
    if (working.size != new.size) {
        return StructureDiffResult.FULL
    }
    for (i in new.indices) {
        if (working[i] != new[i].id) {
            return StructureDiffResult.FULL
        }
    }
    return StructureDiffResult(
        isFull = false,
        ops = removalOps + placementOps,
    )
}

private fun longestIncreasingSubsequence(seq: IntArray): IntArray {
    if (seq.isEmpty()) {
        return IntArray(0)
    }
    // tails[k] = index into seq of the smallest known tail
    // of an increasing subsequence of length k + 1.
    val tails = IntArray(seq.size)
    val prev = IntArray(seq.size) { -1 }
    var size = 0
    for (i in seq.indices) {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (seq[tails[mid]] < seq[i]) {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        if (lo > 0) {
            prev[i] = tails[lo - 1]
        }
        tails[lo] = i
        if (lo == size) {
            size += 1
        }
    }
    val out = IntArray(size)
    var k = tails[size - 1]
    for (j in size - 1 downTo 0) {
        out[j] = k
        k = prev[k]
    }
    return out
}
