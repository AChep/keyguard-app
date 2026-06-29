package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.EntitySyncPlan
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.EntityTypeOutcome
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncAction
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncExecutionResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.entity.KeePassCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.entity.KeePassFolder
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncExecutor
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncPlanBuilder
import com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy.EntitySyncStrategy
import com.artemchep.keyguard.provider.bitwarden.sync.v2.throwIfCancellation

/**
 * Reconciles a KeePass database as a single tree rather than two independent
 * entity-sync runs.
 *
 * KeePass entries live inside groups in a tree, so an entry can only be placed
 * once its group exists with a known UUID. This executor:
 *
 * 1. Applies folder (group) actions first, **ordered by tree depth** so a
 *    parent group is always created before a child group or entry references
 *    it (and deletes run children-first).
 * 2. Then applies cipher (entry) actions, whose target groups now exist.
 *
 * It deliberately reuses the proven diff engine ([EntitySyncPlanBuilder] /
 * [com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncDiffer]) and the
 * shared [EntitySyncExecutor] for per-action apply + OCC + write-back staging —
 * only the action ordering and the two-phase orchestration are KeePass-specific.
 *
 * Unlike the previous design there is **no all-or-nothing gate**: cipher sync
 * runs regardless of folder-sync outcome, and a cipher whose target group is
 * missing falls back to the root group (the existing folderless-cipher
 * behavior) instead of blocking every other cipher.
 */
class KeePassTreeSyncExecutor(
    private val diagnostics: KeePassSyncDiagnostics,
) {
    class FolderInputs(
        val localFolders: List<BitwardenFolder>,
        val remoteFolders: List<KeePassFolder>,
        val strategy: EntitySyncStrategy<BitwardenFolder, KeePassFolder>,
        val ops: EntitySyncOps<BitwardenFolder, KeePassFolder>,
    )

    class CipherInputs(
        val localCiphers: List<BitwardenCipher>,
        val remoteCiphers: List<KeePassCipher>,
        val strategy: EntitySyncStrategy<BitwardenCipher, KeePassCipher>,
        val ops: EntitySyncOps<BitwardenCipher, KeePassCipher>,
    )

    /**
     * @param folders the folder sync inputs, applied first.
     * @param cipherInputs builds the cipher sync inputs; invoked **after** the
     *   folders are applied so the folder→group-UUID mapping it depends on
     *   reflects the freshly-staged groups.
     */
    suspend fun execute(
        folders: FolderInputs,
        cipherInputs: () -> CipherInputs,
    ): SyncResult {
        val folderOutcome = runEntity(
            name = "folder",
            localCount = folders.localFolders.size,
            serverCount = folders.remoteFolders.size,
        ) {
            val plan = EntitySyncPlanBuilder(folders.strategy)
                .buildPlan(
                    localEntities = folders.localFolders,
                    serverEntities = folders.remoteFolders,
                )
                .orderedByTreeDepth()
            diagnostics.entityPlanBuilt(entityName = "folder", plan = plan)
            EntitySyncExecutor(
                entityName = "folder",
                strategy = folders.strategy,
                ops = folders.ops,
                diagnostics = diagnostics,
            ).execute(plan)
        }

        val ciphers = cipherInputs()
        val cipherOutcome = runEntity(
            name = "cipher",
            localCount = ciphers.localCiphers.size,
            serverCount = ciphers.remoteCiphers.size,
        ) {
            val plan = EntitySyncPlanBuilder(ciphers.strategy)
                .buildPlan(
                    localEntities = ciphers.localCiphers,
                    serverEntities = ciphers.remoteCiphers,
                )
            diagnostics.entityPlanBuilt(entityName = "cipher", plan = plan)
            EntitySyncExecutor(
                entityName = "cipher",
                strategy = ciphers.strategy,
                ops = ciphers.ops,
                diagnostics = diagnostics,
            ).execute(plan)
        }

        return SyncResult(
            outcomes = mapOf(
                "folder" to folderOutcome,
                "cipher" to cipherOutcome,
            ),
        )
    }

    private suspend fun runEntity(
        name: String,
        localCount: Int,
        serverCount: Int,
        block: suspend () -> SyncExecutionResult,
    ): EntityTypeOutcome {
        diagnostics.entitySnapshot(
            entityName = name,
            localCount = localCount,
            serverCount = serverCount,
        )
        return try {
            val result = block()
            diagnostics.entityCompleted(entityName = name, result = result)
            EntityTypeOutcome.Completed(result)
        } catch (e: Throwable) {
            e.throwIfCancellation()
            diagnostics.entityFailed(entityName = name, error = e)
            EntityTypeOutcome.Failed(error = e)
        }
    }
}

/**
 * Returns a copy of the folder plan whose actions are ordered for tree-valid
 * application: creating/updating/pushing parents before children (depth
 * ascending) and deleting children before parents (depth descending).
 *
 * [EntitySyncExecutor] partitions actions by type but preserves their relative
 * order within each type, so emitting one correctly-ordered list is enough.
 */
private fun EntitySyncPlan<BitwardenFolder, KeePassFolder>.orderedByTreeDepth(): EntitySyncPlan<BitwardenFolder, KeePassFolder> {
    val byLocalId = localSnapshot.entitiesByLocalId
    val byServerId = serverSnapshot.entitiesById

    fun localDepth(localId: String): Int {
        var depth = 0
        var current = byLocalId[localId]
        val seen = HashSet<String>()
        while (current != null && seen.add(current.folderId)) {
            val parentId = current.parentId ?: break
            current = byLocalId[parentId]
            depth++
        }
        return depth
    }

    fun remoteDepth(serverId: String): Int {
        var depth = 0
        var current = byServerId[serverId]
        val seen = HashSet<String>()
        while (current != null && seen.add(current.group.uuid.toString())) {
            val parentUuid = current.parentGroupUuid?.toString() ?: break
            current = byServerId[parentUuid]
            depth++
        }
        return depth
    }

    fun depthOf(action: SyncAction): Int = when (action) {
        is SyncAction.InsertLocally -> remoteDepth(action.serverId)
        is SyncAction.UpdateLocally -> localDepth(action.localId)
        is SyncAction.PushToServer -> localDepth(action.localId)
        is SyncAction.MergeConflict -> localDepth(action.localId)
        is SyncAction.DeleteLocally -> localDepth(action.localId)
        is SyncAction.DeleteOnServer -> localDepth(action.localId)
    }

    fun isDelete(action: SyncAction): Boolean =
        action is SyncAction.DeleteLocally || action is SyncAction.DeleteOnServer

    val ordered = buildList {
        addAll(actions.filterNot(::isDelete).sortedBy(::depthOf))
        addAll(actions.filter(::isDelete).sortedByDescending(::depthOf))
    }
    return copy(actions = ordered)
}
