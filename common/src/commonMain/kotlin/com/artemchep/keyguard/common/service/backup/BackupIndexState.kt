package com.artemchep.keyguard.common.service.backup

/**
 * The catalog of a repository: its index heads merged into one [index], and
 * the ids of those heads, which the next index names as its parents.
 */
internal data class BackupIndexState(
    val index: BackupIndex,
    val parentIndexIds: Set<String>,
)

/**
 * Merges the [indexes] a [BackupRepository.readIndexes] call returned. The
 * heads are the indexes no other one names as a parent; without any, the
 * newest generation stands in. An empty catalog when nothing is readable.
 */
internal fun mergeBackupIndexes(
    indexes: List<BackupIndex>,
): BackupIndexState {
    val validIndexes = indexes
        .filter { it.indexId.isNotBlank() }
    if (validIndexes.isEmpty()) {
        return BackupIndexState(
            index = BackupIndex(),
            parentIndexIds = emptySet(),
        )
    }

    val referencedParentIds = validIndexes
        .flatMap { it.parentIndexIds }
        .toSet()
    val heads = validIndexes
        .filter { it.indexId !in referencedParentIds }
        .ifEmpty {
            val latestGeneration = validIndexes.maxOf { it.generation }
            validIndexes.filter { it.generation == latestGeneration }
        }
    return BackupIndexState(
        index = mergeIndexHeads(heads),
        parentIndexIds = heads
            .map { it.indexId }
            .toSet(),
    )
}

private fun mergeIndexHeads(
    heads: List<BackupIndex>,
): BackupIndex {
    require(heads.isNotEmpty()) {
        "Backup index merge requires at least one head."
    }
    if (heads.size == 1) {
        return heads.first()
    }

    val snapshots = mergeByKey(
        heads = heads,
        label = "snapshot",
        values = { it.snapshots },
    )
    val blobs = mergeByKey(
        heads = heads,
        label = "blob",
        values = { it.blobs },
    )
    val attachments = heads
        .flatMap { it.attachments.entries }
        .groupBy { it.key }
        .mapNotNull { (fingerprint, entries) ->
            val selected = entries
                .map { it.value }
                .filter { it.blobId in blobs }
                .maxWithOrNull(
                    compareBy<BackupIndexAttachment> { it.lastSeenAt }
                        .thenBy { it.blobId },
                )
                ?: return@mapNotNull null
            fingerprint to selected
        }
        .toMap()

    return BackupIndex(
        generation = heads.maxOf { it.generation },
        updatedAt = heads
            .mapNotNull { it.updatedAt }
            .maxOrNull(),
        snapshots = snapshots,
        attachments = attachments,
        blobs = blobs,
    )
}

private fun <T> mergeByKey(
    heads: List<BackupIndex>,
    label: String,
    values: (BackupIndex) -> Map<String, T>,
): Map<String, T> {
    val result = mutableMapOf<String, T>()
    heads.forEach { head ->
        values(head).forEach { (id, value) ->
            val existing = result[id]
            if (existing != null && existing != value) {
                error("Conflicting backup $label '$id' found while merging indexes.")
            }
            result[id] = value
        }
    }
    return result.toMap()
}
