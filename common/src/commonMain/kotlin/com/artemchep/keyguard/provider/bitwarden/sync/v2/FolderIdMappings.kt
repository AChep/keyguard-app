package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.EntityTypeOutcome

/**
 * Bidirectional mapping between local and remote folder IDs.
 *
 * Built after folder sync completes so that cipher sync can
 * translate folder references in both directions.
 *
 * @param localToRemoteFolders local folder ID → remote folder ID
 *   (nullable: a folder created locally but not yet pushed has no remote ID).
 * @param remoteToLocalFolders remote folder ID → local folder ID.
 */
internal data class FolderIdMappings(
    val localToRemoteFolders: Map<String, String?>,
    val remoteToLocalFolders: Map<String, String>,
)

/**
 * Asserts that folder sync completed cleanly before cipher sync begins.
 *
 * Cipher sync depends on accurate folder ID mappings. If any folder
 * action failed or was skipped, the mappings may be stale and ciphers
 * could be assigned to wrong folders. This function throws on any
 * non-clean folder outcome.
 */
internal fun requireFolderSyncCompletedBeforeCiphers(folderResult: EntityTypeOutcome) {
    when (folderResult) {
        is EntityTypeOutcome.Failed -> throw folderResult.error
        is EntityTypeOutcome.Completed -> {
            val result = folderResult.result
            if (result.failures.isEmpty() && result.skipped == 0) return

            val failureCount = result.failures.size
            val skippedCount = result.skipped
            val cause = result.failures.firstOrNull()?.error
            throw IllegalStateException(
                "Folder sync did not complete cleanly: " +
                    "$failureCount action failure(s), $skippedCount skipped action(s).",
                cause,
            )
        }
    }
}

/**
 * Builds [FolderIdMappings] from the current local folder list.
 * Only folders belonging to [accountId] are included.
 */
internal fun buildFolderIdMappings(
    accountId: String,
    folders: List<BitwardenFolder>,
): FolderIdMappings {
    val accountFolders =
        folders.filterByAccountId(
            accountId = accountId,
            getAccountId = BitwardenFolder::accountId,
        )
    val localToRemoteFolders =
        accountFolders.associate { folder ->
            val remoteId =
                folder.service.remote
                    ?.id
            val localId = folder.folderId
            localId to remoteId
        }
    val remoteToLocalFolders =
        accountFolders
            .mapNotNull { folder ->
                val remoteId =
                    folder.service.remote
                        ?.id
                        ?: return@mapNotNull null
                val localId = folder.folderId
                remoteId to localId
            }.toMap()
    return FolderIdMappings(
        localToRemoteFolders = localToRemoteFolders,
        remoteToLocalFolders = remoteToLocalFolders,
    )
}
