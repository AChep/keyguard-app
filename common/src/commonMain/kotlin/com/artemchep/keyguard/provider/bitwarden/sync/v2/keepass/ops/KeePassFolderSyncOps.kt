package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.ops

import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.KeePassDbMutator
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.entity.KeePassFolder
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.KeePassWriteBackBuffer
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec.KeePassFolderCodec
import app.keemobile.kotpass.models.Group
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.writeIfCurrent
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateEntry
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.RemoteWriteOutcome
import kotlin.uuid.Uuid

class KeePassFolderSyncOps(
    private val accountId: String,
    private val buffer: KeePassWriteBackBuffer,
    private val cryptoGenerator: CryptoGenerator,
    private val folderCodec: KeePassFolderCodec,
    private val mutator: KeePassDbMutator,
) : EntitySyncOps<BitwardenFolder, KeePassFolder> {
    override suspend fun readLocal(localId: String): BitwardenFolder? =
        buffer.readFolder(localId)

    override suspend fun insertOrUpdateLocally(entries: List<Pair<KeePassFolder, BitwardenFolder?>>) {
        val decoded = decodeFolders(entries)
        decoded.forEach(buffer::stageFolderUpsert)
    }

    override suspend fun updateLocally(
        entries: List<LocalUpdateEntry<KeePassFolder, BitwardenFolder>>,
    ): LocalUpdateResult {
        val remoteToLocalFolders = currentRemoteToLocalFolders().toMutableMap()
        val decoded = entries.map { entry ->
            entry to decodeFolder(
                server = entry.server,
                local = entry.initialLocal,
                remoteToLocalFolders = remoteToLocalFolders,
            )
        }
        var applied = 0
        var skipped = 0
        decoded.forEach { (entry, folder) ->
            val current = buffer.readFolder(entry.localId)
            if (entry.writeIfCurrent(current) { buffer.stageFolderUpsert(folder) }) {
                applied++
            } else {
                skipped++
            }
        }
        return LocalUpdateResult(applied = applied, skipped = skipped)
    }

    override suspend fun deleteLocally(localIds: List<String>) {
        val idsToDelete = collectFolderIdsWithDescendants(localIds)
        idsToDelete.forEach(buffer::stageFolderDelete)
    }

    override suspend fun saveLocal(local: BitwardenFolder, previousLocal: BitwardenFolder?) {
        buffer.stageFolderUpsert(local)
    }

    override suspend fun pushToServer(
        local: BitwardenFolder,
        server: KeePassFolder?,
        force: Boolean,
    ): RemoteWriteOutcome<BitwardenFolder> {
        val remoteUuid = server?.group?.uuid
        val mappings = currentFolderIdMappings()
        val targetParentUuid = resolveTargetParentUuid(
            folder = local,
            mappings = mappings,
        )

        if (remoteUuid != null) {
            val updated = mutator.modifyGroup(remoteUuid) {
                copy(name = local.name)
            }
            val shouldMove = targetParentUuid != server.parentGroupUuid
            if (shouldMove) {
                val moved = mutator.moveGroup(remoteUuid, targetParentUuid)
                check(moved) {
                    "Could not move KeePass group $remoteUuid to parent $targetParentUuid."
                }
            }
            if (updated) {
                val newLocal = local.copy(
                    service = BitwardenService(
                        remote = BitwardenService.Remote(
                            id = remoteUuid.toString(),
                            revisionDate = local.revisionDate,
                            deletedDate = null,
                        ),
                        version = BitwardenService.VERSION,
                    ),
                    hierarchyMode = FolderHierarchyMode.ParentId,
                )
                return RemoteWriteOutcome.Upsert(newLocal)
            }
        }

        val newUuid = Uuid.random()
        mutator.addGroup(
            Group(
                uuid = newUuid,
                name = local.name,
            ),
            parentGroupUuid = targetParentUuid,
        )
        val newLocal = local.copy(
            service = BitwardenService(
                remote = BitwardenService.Remote(
                    id = newUuid.toString(),
                    revisionDate = local.revisionDate,
                    deletedDate = null,
                ),
                version = BitwardenService.VERSION,
            ),
            hierarchyMode = FolderHierarchyMode.ParentId,
        )
        return RemoteWriteOutcome.Upsert(newLocal)
    }

    override suspend fun deleteOnServer(
        local: BitwardenFolder,
        serverId: String,
    ): RemoteWriteOutcome<BitwardenFolder> {
        // Orphan the folder's ciphers (and any surviving sub-folders) to the
        // root group rather than cascade-deleting them: deleting a folder must
        // not destroy the entries it held. The kept ciphers reconcile to
        // folderId=null once the post-folder-phase cipher snapshot sees them in
        // the root group (see KeePassSyncCoordinator.cipherInputs).
        mutator.orphanAndRemoveGroup(Uuid.parse(serverId))
        return RemoteWriteOutcome.DeleteLocal
    }

    override suspend fun mergeConflict(
        local: BitwardenFolder,
        server: KeePassFolder,
    ): RemoteWriteOutcome<BitwardenFolder> {
        val remoteToLocalFolders = currentRemoteToLocalFolders()
        val decoded = folderCodec.decode(
            accountId = accountId,
            folderId = local.folderId,
            remote = server.group,
            local = local,
            revisionDate = server.revisionDate,
            parentId = server.parentGroupUuid
                ?.toString()
                ?.let(remoteToLocalFolders::get),
        )
        return RemoteWriteOutcome.Upsert(decoded)
    }

    private fun decodeFolders(
        entries: List<Pair<KeePassFolder, BitwardenFolder?>>,
    ): List<BitwardenFolder> {
        val remoteToLocalFolders = currentRemoteToLocalFolders().toMutableMap()
        return entries.map { (server, local) ->
            decodeFolder(
                server = server,
                local = local,
                remoteToLocalFolders = remoteToLocalFolders,
            )
        }
    }

    private fun decodeFolder(
        server: KeePassFolder,
        local: BitwardenFolder?,
        remoteToLocalFolders: MutableMap<String, String>,
    ): BitwardenFolder {
        val folderId = local?.folderId
            ?: remoteToLocalFolders[server.id]
            ?: cryptoGenerator.uuid()
        remoteToLocalFolders[server.id] = folderId
        val parentId = server.parentGroupUuid
            ?.toString()
            ?.let(remoteToLocalFolders::get)
        return folderCodec.decode(
            accountId = accountId,
            folderId = folderId,
            remote = server.group,
            local = local,
            revisionDate = server.revisionDate,
            parentId = parentId,
        )
    }

    private data class FolderIdMappings(
        val localToRemote: Map<String, String?>,
        val remoteToLocal: Map<String, String>,
    )

    private fun currentFolderIdMappings(): FolderIdMappings {
        val folders = buffer.listFolders(accountId = accountId)
        val localToRemote = folders.associate { folder ->
            folder.folderId to folder.service.remote?.id
        }
        val remoteToLocal = folders.mapNotNull { folder ->
            val remoteId = folder.service.remote?.id
                ?: return@mapNotNull null
            remoteId to folder.folderId
        }.toMap()
        return FolderIdMappings(
            localToRemote = localToRemote,
            remoteToLocal = remoteToLocal,
        )
    }

    private fun currentRemoteToLocalFolders(): Map<String, String> =
        currentFolderIdMappings().remoteToLocal

    private fun resolveTargetParentUuid(
        folder: BitwardenFolder,
        mappings: FolderIdMappings,
    ): Uuid? {
        val parentId = folder.parentId
            ?: return null
        check(parentId != folder.folderId) {
            "KeePass group can not be its own parent."
        }
        val remoteId = if (parentId in mappings.localToRemote) {
            mappings.localToRemote[parentId]
        } else {
            parentId.takeIf { it in mappings.remoteToLocal }
        }
        check(remoteId != null) {
            "Parent folder $parentId has no KeePass remote group."
        }
        return Uuid.parse(remoteId)
    }

    private fun collectFolderIdsWithDescendants(
        localIds: Collection<String>,
    ): Set<String> {
        val folders = buffer.listFolders(accountId = accountId)
        val childrenByParent = folders.groupBy { it.parentId }
        val result = linkedSetOf<String>()
        fun collect(id: String) {
            if (!result.add(id)) return
            childrenByParent[id]
                .orEmpty()
                .forEach { child -> collect(child.folderId) }
        }
        localIds.forEach(::collect)
        return result
    }
}
