package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.usecase.AddFolder
import com.artemchep.keyguard.common.usecase.AddFolderRequest
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyDatabase
import kotlin.time.Clock

/**
 * @author Artem Chepurnyi
 */
class AddFolderImpl(
    private val modifyDatabase: ModifyDatabase,
    private val cryptoGenerator: CryptoGenerator,
) : AddFolder {
    companion object {
        private const val TAG = "AddFolder.bitwarden"
    }

    override fun invoke(
        requests: Collection<AddFolderRequest>,
    ): IO<List<String>> = modifyDatabase { database ->
        val dao = database.folderQueries
        val now = Clock.System.now()

        val models = requests
            .map { request ->
                val folderId = cryptoGenerator.uuid()
                BitwardenFolder(
                    accountId = request.accountId.id,
                    folderId = folderId,
                    revisionDate = now,
                    name = request.name,
                    parentId = request.parentId,
                    hierarchyMode = request.hierarchyMode,
                    service = BitwardenService(
                        version = BitwardenService.VERSION,
                    ),
                )
            }
        if (models.isEmpty()) {
            return@modifyDatabase ModifyDatabase.Result(
                changedAccountIds = emptySet(),
                value = emptyList(),
            )
        }
        dao.transaction {
            models.forEach { folder ->
                dao.insert(
                    folderId = folder.folderId,
                    accountId = folder.accountId,
                    data = folder,
                )
            }
        }

        val changedAccountIds = models
            .map { AccountId(it.accountId) }
            .toSet()
        ModifyDatabase.Result(
            changedAccountIds = changedAccountIds,
            // Follows the request order,
            // as the interface promises.
            value = models.map { it.folderId },
        )
    }
}
