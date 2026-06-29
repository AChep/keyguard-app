package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.entity.CipherEntity
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.ServerItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncAction
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncDiffer
import com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.strategy.CipherSyncStrategy
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class SyncDifferV2Test {
    @Test
    fun `server item missing locally inserts without creating pending work`() {
        val actions = diff(
            local = emptyList(),
            server = listOf(serverMeta(id = "remote-1", revisionDate = T1)),
        )

        assertEquals(
            listOf(SyncAction.InsertLocally(serverId = "remote-1")),
            actions,
        )
    }

    @Test
    fun `local only item pushes as new remote item`() {
        val actions = diff(
            local = listOf(localMeta(localId = "local-1", remoteId = null, revisionDate = T1)),
            server = emptyList(),
        )

        assertEquals(
            listOf(
                SyncAction.PushToServer(
                    localId = "local-1",
                    serverId = null,
                ),
            ),
            actions,
        )
    }

    @Test
    fun `unchanged local and remote item is a clean no-op`() {
        val actions = diff(
            local = listOf(localMeta()),
            server = listOf(serverMeta()),
        )

        assertEquals(emptyList(), actions)
    }

    @Test
    fun `remote update on clean local item updates locally`() {
        val actions = diff(
            local = listOf(localMeta()),
            server = listOf(serverMeta(revisionDate = T1)),
        )

        assertEquals(
            listOf(
                SyncAction.UpdateLocally(
                    localId = "local-1",
                    serverId = "remote-1",
                ),
            ),
            actions,
        )
    }

    @Test
    fun `local update with unchanged remote pushes to server`() {
        val actions = diff(
            local = listOf(localMeta(revisionDate = T1)),
            server = listOf(serverMeta()),
        )

        assertEquals(
            listOf(
                SyncAction.PushToServer(
                    localId = "local-1",
                    serverId = "remote-1",
                ),
            ),
            actions,
        )
    }

    @Test
    fun `stale server snapshot on clean local item is ignored and recorded`() {
        val result =
            SyncDiffer.diffResult(
                localItems =
                    listOf(
                        localMeta(
                            revisionDate = T2,
                            lastSyncedRevisionDate = T2,
                        ),
                    ),
                serverItems = listOf(serverMeta(revisionDate = T1)),
            )

        assertEquals(emptyList(), result.actions)
        assertEquals(1, result.staleServerEntities)
    }

    @Test
    fun `stale server snapshot on dirty local item pushes local and records stale entity`() {
        val result =
            SyncDiffer.diffResult(
                localItems =
                    listOf(
                        localMeta(
                            revisionDate = T3,
                            lastSyncedRevisionDate = T2,
                        ),
                    ),
                serverItems = listOf(serverMeta(revisionDate = T1)),
            )

        assertEquals(
            listOf(
                SyncAction.PushToServer(
                    localId = "local-1",
                    serverId = "remote-1",
                ),
            ),
            result.actions,
        )
        assertEquals(1, result.staleServerEntities)
    }

    @Test
    fun `pending local deletion is sent as a remote delete`() {
        val actions = diff(
            local = listOf(
                localMeta(
                    revisionDate = T1,
                    deletedDate = T1,
                    isLocallyDeleted = true,
                ),
            ),
            server = listOf(serverMeta()),
        )

        assertEquals(
            listOf(
                SyncAction.DeleteOnServer(
                    localId = "local-1",
                    serverId = "remote-1",
                ),
            ),
            actions,
        )
    }

    @Test
    fun `remote missing existing local item deletes local copy`() {
        val actions = diff(
            local = listOf(localMeta()),
            server = emptyList(),
        )

        assertEquals(
            listOf(SyncAction.DeleteLocally(localId = "local-1")),
            actions,
        )
    }

    @Test
    fun `local only item already deleted is discarded locally`() {
        val actions = diff(
            local = listOf(
                localMeta(
                    localId = "local-1",
                    remoteId = null,
                    revisionDate = T1,
                    isLocallyDeleted = true,
                ),
            ),
            server = emptyList(),
        )

        assertEquals(
            listOf(SyncAction.DeleteLocally(localId = "local-1")),
            actions,
        )
    }

    @Test
    fun `mergeable local and remote divergence emits merge conflict`() {
        val actions = diff(
            local = listOf(
                localMeta(
                    revisionDate = T1,
                    lastSyncedRevisionDate = T0,
                    isMergeable = true,
                ),
            ),
            server = listOf(serverMeta(revisionDate = T2)),
        )

        assertEquals(
            listOf(
                SyncAction.MergeConflict(
                    localId = "local-1",
                    serverId = "remote-1",
                ),
            ),
            actions,
        )
    }

    @Test
    fun `non-mergeable local and remote divergence keeps remote canonical`() {
        val actions = diff(
            local = listOf(
                localMeta(
                    revisionDate = T1,
                    lastSyncedRevisionDate = T0,
                    isMergeable = false,
                ),
            ),
            server = listOf(serverMeta(revisionDate = T2)),
        )

        assertEquals(
            listOf(
                SyncAction.UpdateLocally(
                    localId = "local-1",
                    serverId = "remote-1",
                ),
            ),
            actions,
        )
    }

    @Test
    fun `duplicate local records for one remote id keep newest and remove stale duplicate`() {
        val actions = diff(
            local = listOf(
                localMeta(
                    localId = "old-local",
                    remoteId = "remote-1",
                    revisionDate = T0,
                    lastSyncedRevisionDate = T0,
                ),
                localMeta(
                    localId = "new-local",
                    remoteId = "remote-1",
                    revisionDate = T1,
                    lastSyncedRevisionDate = T1,
                ),
            ),
            server = listOf(serverMeta(id = "remote-1", revisionDate = T1)),
        )

        assertEquals(
            listOf(SyncAction.DeleteLocally(localId = "old-local")),
            actions,
        )
    }

    @Test
    fun `older service metadata version forces a local refresh`() {
        val actions = diff(
            local = listOf(
                localMeta(
                    serviceVersion = BitwardenService.VERSION - 1,
                ),
            ),
            server = listOf(serverMeta()),
        )

        assertEquals(
            listOf(
                SyncAction.UpdateLocally(
                    localId = "local-1",
                    serverId = "remote-1",
                    force = true,
                ),
            ),
            actions,
        )
    }

    @Test
    fun `timestamp precision rounding mismatch refreshes locally instead of pushing`() {
        val localInstant = Instant.fromEpochMilliseconds(1_000L)
        val serverInstant = Instant.fromEpochMilliseconds(1_040L)

        val actions = diff(
            local = listOf(
                localMeta(
                    revisionDate = localInstant,
                    lastSyncedRevisionDate = serverInstant,
                ),
            ),
            server = listOf(serverMeta(revisionDate = serverInstant)),
        )

        assertEquals(
            listOf(
                SyncAction.UpdateLocally(
                    localId = "local-1",
                    serverId = "remote-1",
                ),
            ),
            actions,
        )
    }

    @Test
    fun `cipher folder drift ignores different local and remote id namespaces`() {
        val strategy =
            CipherSyncStrategy(
                remoteFolderIdToLocalId = mapOf("folder-remote" to "folder-local")::get,
            )

        val actions =
            SyncDiffer.diff(
                localItems = listOf(
                    strategy.toLocalItemMeta(syncedCipher(folderId = "folder-local")),
                ),
                serverItems = listOf(
                    strategy.toServerItemMeta(cipherEntity(folderId = "folder-remote")),
                ),
            )

        assertEquals(emptyList(), actions)
    }

    @Test
    fun `cipher folder drift refreshes when server folder differs`() {
        val strategy =
            CipherSyncStrategy(
                remoteFolderIdToLocalId = mapOf(
                    "folder-remote" to "folder-local",
                    "folder-remote-new" to "folder-local-new",
                )::get,
            )

        val actions =
            SyncDiffer.diff(
                localItems = listOf(
                    strategy.toLocalItemMeta(syncedCipher(folderId = "folder-local")),
                ),
                serverItems = listOf(
                    strategy.toServerItemMeta(cipherEntity(folderId = "folder-remote-new")),
                ),
            )

        assertEquals(
            listOf(
                SyncAction.UpdateLocally(
                    localId = "local-1",
                    serverId = "remote-1",
                ),
            ),
            actions,
        )
    }

    @Test
    fun `cipher folder drift refreshes when server moves cipher into a folder`() {
        val strategy =
            CipherSyncStrategy(
                remoteFolderIdToLocalId = mapOf("folder-remote" to "folder-local")::get,
            )

        val actions =
            SyncDiffer.diff(
                localItems = listOf(
                    strategy.toLocalItemMeta(syncedCipher()),
                ),
                serverItems = listOf(
                    strategy.toServerItemMeta(cipherEntity(folderId = "folder-remote")),
                ),
            )

        assertEquals(
            listOf(
                SyncAction.UpdateLocally(
                    localId = "local-1",
                    serverId = "remote-1",
                ),
            ),
            actions,
        )
    }

    @Test
    fun `cipher folder drift refreshes when server removes the folder`() {
        val strategy =
            CipherSyncStrategy(
                remoteFolderIdToLocalId = mapOf("folder-remote" to "folder-local")::get,
            )

        val actions =
            SyncDiffer.diff(
                localItems = listOf(
                    strategy.toLocalItemMeta(syncedCipher(folderId = "folder-local")),
                ),
                serverItems = listOf(
                    strategy.toServerItemMeta(cipherEntity()),
                ),
            )

        assertEquals(
            listOf(
                SyncAction.UpdateLocally(
                    localId = "local-1",
                    serverId = "remote-1",
                ),
            ),
            actions,
        )
    }

    @Test
    fun `cipher folder drift refreshes when server folder cannot be resolved locally`() {
        val strategy =
            CipherSyncStrategy(
                remoteFolderIdToLocalId = emptyMap<String, String>()::get,
            )

        val actions =
            SyncDiffer.diff(
                localItems = listOf(
                    strategy.toLocalItemMeta(syncedCipher(folderId = "folder-local")),
                ),
                serverItems = listOf(
                    strategy.toServerItemMeta(cipherEntity(folderId = "folder-remote")),
                ),
            )

        assertEquals(
            listOf(
                SyncAction.UpdateLocally(
                    localId = "local-1",
                    serverId = "remote-1",
                ),
            ),
            actions,
        )
    }

    @Test
    fun `cipher folder drift converges once an unresolvable server folder is decoded`() {
        val strategy =
            CipherSyncStrategy(
                remoteFolderIdToLocalId = emptyMap<String, String>()::get,
            )

        // Decoding a server cipher whose folder has no local mapping
        // produces a cipher without a folder; the next sync must not
        // flag the same drift again.
        val actions =
            SyncDiffer.diff(
                localItems = listOf(
                    strategy.toLocalItemMeta(syncedCipher()),
                ),
                serverItems = listOf(
                    strategy.toServerItemMeta(cipherEntity(folderId = "folder-remote")),
                ),
            )

        assertEquals(emptyList(), actions)
    }

    @Test
    fun `matching dates with decoded drift forces a local refresh`() {
        val actions = diff(
            local = listOf(
                localMeta(
                    attachmentIds = setOf("attachment-a"),
                    localFolderId = "folder-a",
                    favorite = false,
                    collectionIds = setOf("collection-a"),
                ),
            ),
            server = listOf(
                serverMeta(
                    attachmentIds = setOf("attachment-b"),
                    localFolderId = "folder-b",
                    favorite = true,
                    collectionIds = setOf("collection-b"),
                ),
            ),
        )

        assertEquals(
            listOf(
                SyncAction.UpdateLocally(
                    localId = "local-1",
                    serverId = "remote-1",
                ),
            ),
            actions,
        )
    }

    @Test
    fun `retryable local error with newer local revision is retried as push`() {
        val actions = diff(
            local = listOf(
                localMeta(
                    revisionDate = T1,
                    hasError = true,
                    canRetryError = true,
                ),
            ),
            server = listOf(serverMeta()),
        )

        assertEquals(
            listOf(
                SyncAction.PushToServer(
                    localId = "local-1",
                    serverId = "remote-1",
                ),
            ),
            actions,
        )
    }

    @Test
    fun `non-retryable local error blocks automatic push`() {
        val actions = diff(
            local = listOf(
                localMeta(
                    revisionDate = T1,
                    hasError = true,
                    canRetryError = false,
                ),
            ),
            server = listOf(serverMeta()),
        )

        assertEquals(emptyList(), actions)
    }

    @Test
    fun `local refresh marker updates matching-date item`() {
        val actions = diff(
            local = listOf(
                localMeta(
                    requiresLocalRefreshWhenDatesMatch = true,
                ),
            ),
            server = listOf(serverMeta()),
        )

        assertEquals(
            listOf(
                SyncAction.UpdateLocally(
                    localId = "local-1",
                    serverId = "remote-1",
                ),
            ),
            actions,
        )
    }

    @Test
    fun `force push repair marker pushes matching-date item`() {
        val actions = diff(
            local = listOf(
                localMeta(
                    requiresForcePushWhenDatesMatch = true,
                ),
            ),
            server = listOf(serverMeta()),
        )

        assertEquals(
            listOf(
                SyncAction.PushToServer(
                    localId = "local-1",
                    serverId = "remote-1",
                    force = true,
                ),
            ),
            actions,
        )
    }

    @Test
    fun `push marker pushes matching-date item without forcing server body`() {
        val actions = diff(
            local = listOf(
                localMeta(
                    requiresPushWhenDatesMatch = true,
                ),
            ),
            server = listOf(serverMeta()),
        )

        assertEquals(
            listOf(
                SyncAction.PushToServer(
                    localId = "local-1",
                    serverId = "remote-1",
                    force = false,
                ),
            ),
            actions,
        )
    }

    @Test
    fun `force push repair marker wins over pending attachment push marker`() {
        val actions = diff(
            local = listOf(
                localMeta(
                    pendingLocalAttachmentIds = setOf("local-upload"),
                    requiresPushWhenDatesMatch = true,
                    requiresForcePushWhenDatesMatch = true,
                ),
            ),
            server = listOf(serverMeta()),
        )

        assertEquals(
            listOf(
                SyncAction.PushToServer(
                    localId = "local-1",
                    serverId = "remote-1",
                    force = true,
                ),
            ),
            actions,
        )
    }

    @Test
    fun `force push repair marker refreshes matching-date item with attachment drift`() {
        val actions = diff(
            local = listOf(
                localMeta(
                    attachmentIds = setOf("attachment-local"),
                    requiresForcePushWhenDatesMatch = true,
                ),
            ),
            server = listOf(
                serverMeta(
                    attachmentIds = setOf("attachment-server"),
                ),
            ),
        )

        assertEquals(
            listOf(
                SyncAction.UpdateLocally(
                    localId = "local-1",
                    serverId = "remote-1",
                ),
            ),
            actions,
        )
    }

    @Test
    fun `force push repair marker refreshes matching-date item with folder drift`() {
        val actions = diff(
            local = listOf(
                localMeta(
                    localFolderId = "folder-local",
                    requiresForcePushWhenDatesMatch = true,
                ),
            ),
            server = listOf(
                serverMeta(
                    localFolderId = "folder-server",
                ),
            ),
        )

        assertEquals(
            listOf(
                SyncAction.UpdateLocally(
                    localId = "local-1",
                    serverId = "remote-1",
                ),
            ),
            actions,
        )
    }

    @Test
    fun `force push repair marker refreshes matching-date item with favorite drift`() {
        val actions = diff(
            local = listOf(
                localMeta(
                    favorite = false,
                    requiresForcePushWhenDatesMatch = true,
                ),
            ),
            server = listOf(
                serverMeta(
                    favorite = true,
                ),
            ),
        )

        assertEquals(
            listOf(
                SyncAction.UpdateLocally(
                    localId = "local-1",
                    serverId = "remote-1",
                ),
            ),
            actions,
        )
    }

    @Test
    fun `force push repair marker refreshes matching-date item with collection drift`() {
        val actions = diff(
            local = listOf(
                localMeta(
                    collectionIds = setOf("collection-local"),
                    requiresForcePushWhenDatesMatch = true,
                ),
            ),
            server = listOf(
                serverMeta(
                    collectionIds = setOf("collection-server"),
                ),
            ),
        )

        assertEquals(
            listOf(
                SyncAction.UpdateLocally(
                    localId = "local-1",
                    serverId = "remote-1",
                ),
            ),
            actions,
        )
    }

    @Test
    fun `pending local attachment pushes matching-date item with attachment drift without force`() {
        val actions = diff(
            local = listOf(
                localMeta(
                    attachmentIds = setOf("local-upload"),
                    pendingLocalAttachmentIds = setOf("local-upload"),
                    requiresPushWhenDatesMatch = true,
                ),
            ),
            server = listOf(
                serverMeta(
                    attachmentIds = setOf("remote-upload"),
                ),
            ),
        )

        assertEquals(
            listOf(
                SyncAction.PushToServer(
                    localId = "local-1",
                    serverId = "remote-1",
                    force = false,
                ),
            ),
            actions,
        )
    }

    @Test
    fun `pending local attachment refreshes matching-date item with folder drift`() {
        val actions = diff(
            local = listOf(
                localMeta(
                    localFolderId = "folder-local",
                    pendingLocalAttachmentIds = setOf("local-upload"),
                    requiresPushWhenDatesMatch = true,
                ),
            ),
            server = listOf(
                serverMeta(
                    localFolderId = "folder-server",
                ),
            ),
        )

        assertEquals(
            listOf(
                SyncAction.UpdateLocally(
                    localId = "local-1",
                    serverId = "remote-1",
                ),
            ),
            actions,
        )
    }

    @Test
    fun `pending remote attachment deletion pushes while server still has deleted id`() {
        val actions = diff(
            local = listOf(
                localMeta(
                    attachmentIds = setOf("kept-attachment"),
                    pendingRemoteAttachmentDeletionIds = setOf("deleted-attachment"),
                    requiresPushWhenDatesMatch = true,
                ),
            ),
            server = listOf(
                serverMeta(
                    attachmentIds = setOf("kept-attachment", "deleted-attachment"),
                ),
            ),
        )

        assertEquals(
            listOf(
                SyncAction.PushToServer(
                    localId = "local-1",
                    serverId = "remote-1",
                    force = false,
                ),
            ),
            actions,
        )
    }

    @Test
    fun `pending remote attachment deletion refreshes after server already lacks deleted id`() {
        val actions = diff(
            local = listOf(
                localMeta(
                    attachmentIds = setOf("kept-attachment"),
                    pendingRemoteAttachmentDeletionIds = setOf("deleted-attachment"),
                    requiresPushWhenDatesMatch = true,
                ),
            ),
            server = listOf(
                serverMeta(
                    attachmentIds = setOf("kept-attachment"),
                ),
            ),
        )

        assertEquals(
            listOf(
                SyncAction.UpdateLocally(
                    localId = "local-1",
                    serverId = "remote-1",
                ),
            ),
            actions,
        )
    }

    @Test
    fun `pending remote attachment deletion refreshes after server lacks some deleted ids`() {
        val actions = diff(
            local = listOf(
                localMeta(
                    attachmentIds = setOf("kept-attachment"),
                    pendingRemoteAttachmentDeletionIds =
                        setOf("deleted-attachment-a", "deleted-attachment-b"),
                    requiresPushWhenDatesMatch = true,
                ),
            ),
            server = listOf(
                serverMeta(
                    attachmentIds = setOf("kept-attachment", "deleted-attachment-b"),
                ),
            ),
        )

        assertEquals(
            listOf(
                SyncAction.UpdateLocally(
                    localId = "local-1",
                    serverId = "remote-1",
                ),
            ),
            actions,
        )
    }

    @Test
    fun `cipher pending attachment retry pushes attachment drift without forcing cipher body`() {
        val remoteService =
            BitwardenService(
                remote =
                    BitwardenService.Remote(
                        id = "remote-1",
                        revisionDate = T0,
                        deletedDate = null,
                    ),
                version = BitwardenService.VERSION,
            )
        val remoteCipher =
            cipher(
                service = remoteService,
                attachments =
                    listOf(
                        BitwardenCipher.Attachment.Remote(
                            id = "remote-upload",
                            url = null,
                            fileName = "report.pdf",
                            keyBase64 = "shared-key",
                            size = 42L,
                        ),
                    ),
            )
        val localCipher =
            cipher(
                service =
                    remoteService.copy(
                        error =
                            BitwardenService.Error(
                                code = BitwardenService.Error.CODE_UNKNOWN,
                                revisionDate = T0,
                            ),
                    ),
                attachments =
                    listOf(
                        BitwardenCipher.Attachment.Local(
                            id = "local-upload",
                            url = "file:///tmp/report.pdf",
                            fileName = "report.pdf",
                            size = 10L,
                            keyBase64 = "shared-key",
                            pendingUpload =
                                PendingUploadFile(
                                    path = "/tmp/upload.bin",
                                    plainSize = 10L,
                                    encryptedSize = 42L,
                                    remoteId = "remote-upload",
                                ),
                        ),
                    ),
                remoteEntity = remoteCipher,
            )
        val actions =
            SyncDiffer.diff(
                localItems = listOf(
                    CipherSyncStrategy(
                        remoteFolderIdToLocalId = { null },
                    ).toLocalItemMeta(localCipher),
                ),
                serverItems =
                    listOf(
                        serverMeta(
                            attachmentIds = setOf("remote-upload"),
                        ),
                    ),
            )

        assertEquals(
            listOf(
                SyncAction.PushToServer(
                    localId = "local-1",
                    serverId = "remote-1",
                    force = false,
                ),
            ),
            actions,
        )
    }

    private fun diff(
        local: List<LocalItemMeta>,
        server: List<ServerItemMeta>,
    ): List<SyncAction> =
        SyncDiffer.diff(
            localItems = local,
            serverItems = server,
        )

    private fun localMeta(
        localId: String = "local-1",
        remoteId: String? = "remote-1",
        revisionDate: Instant = T0,
        deletedDate: Instant? = null,
        lastSyncedRevisionDate: Instant? = T0,
        lastSyncedDeletedDate: Instant? = null,
        isLocallyDeleted: Boolean = false,
        isMergeable: Boolean = true,
        serviceVersion: Int = BitwardenService.VERSION,
        hasError: Boolean = false,
        canRetryError: Boolean = true,
        attachmentIds: Set<String>? = null,
        localFolderId: String? = null,
        favorite: Boolean? = null,
        collectionIds: Set<String>? = null,
        requiresLocalRefreshWhenDatesMatch: Boolean = false,
        requiresPushWhenDatesMatch: Boolean = false,
        requiresForcePushWhenDatesMatch: Boolean = false,
        pendingLocalAttachmentIds: Set<String> = emptySet(),
        pendingRemoteAttachmentDeletionIds: Set<String> = emptySet(),
    ): LocalItemMeta =
        LocalItemMeta(
            localId = localId,
            remoteId = remoteId,
            revisionDate = revisionDate,
            deletedDate = deletedDate,
            lastSyncedRevisionDate = lastSyncedRevisionDate,
            lastSyncedDeletedDate = lastSyncedDeletedDate,
            isLocallyDeleted = isLocallyDeleted,
            isMergeable = isMergeable,
            serviceVersion = serviceVersion,
            hasError = hasError,
            canRetryError = canRetryError,
            attachmentIds = attachmentIds,
            localFolderId = localFolderId,
            favorite = favorite,
            collectionIds = collectionIds,
            requiresLocalRefreshWhenDatesMatch = requiresLocalRefreshWhenDatesMatch,
            requiresPushWhenDatesMatch = requiresPushWhenDatesMatch,
            requiresForcePushWhenDatesMatch = requiresForcePushWhenDatesMatch,
            pendingLocalAttachmentIds = pendingLocalAttachmentIds,
            pendingRemoteAttachmentDeletionIds = pendingRemoteAttachmentDeletionIds,
        )

    private fun serverMeta(
        id: String = "remote-1",
        revisionDate: Instant = T0,
        deletedDate: Instant? = null,
        attachmentIds: Set<String>? = null,
        localFolderId: String? = null,
        favorite: Boolean? = null,
        collectionIds: Set<String>? = null,
    ): ServerItemMeta =
        ServerItemMeta(
            id = id,
            revisionDate = revisionDate,
            deletedDate = deletedDate,
            attachmentIds = attachmentIds,
            localFolderId = localFolderId,
            favorite = favorite,
            collectionIds = collectionIds,
        )

    private fun cipher(
        service: BitwardenService,
        attachments: List<BitwardenCipher.Attachment>,
        remoteEntity: BitwardenCipher? = null,
    ): BitwardenCipher =
        BitwardenCipher(
            accountId = "account-1",
            cipherId = "local-1",
            revisionDate = T0,
            createdDate = T0,
            service = service,
            remoteEntity = remoteEntity,
            keyBase64 = "cipher-key",
            name = "Quarterly report",
            notes = "",
            favorite = false,
            attachments = attachments,
            reprompt = BitwardenCipher.RepromptType.None,
            type = BitwardenCipher.Type.SecureNote,
            secureNote = BitwardenCipher.SecureNote(),
        )

    private fun syncedCipher(folderId: String? = null): BitwardenCipher =
        cipher(
            service = testService(remoteId = "remote-1"),
            attachments = emptyList(),
        )
            .copy(folderId = folderId)
            .let { it.copy(remoteEntity = it) }

    private fun cipherEntity(folderId: String? = null): CipherEntity =
        CipherEntity(
            id = "remote-1",
            revisionDate = T0,
            folderId = folderId,
        )
}
