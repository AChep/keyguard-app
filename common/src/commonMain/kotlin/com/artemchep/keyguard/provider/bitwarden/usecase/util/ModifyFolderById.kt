package com.artemchep.keyguard.provider.bitwarden.usecase.util

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.data.bitwarden.Folder
import kotlinx.datetime.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class ModifyFolderById(
    private val modifyDatabase: ModifyDatabase,
) {
    companion object {
        private const val TAG = "ModifyFolderById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifyDatabase = directDI.instance(),
    )

    operator fun invoke(
        folderIds: Set<String>,
        checkIfStub: Boolean = true,
        checkIfChanged: Boolean = true,
        transform: suspend (Folder) -> Folder,
    ): IO<Unit> = modifyDatabase { database ->
        val dao = database.folderQueries
        val now = Clock.System.now()
        val models = dao
            .get()
            .executeAsList()
            .filter {
                it.folderId in folderIds
            }
            .mapNotNull { model ->
                // If the folder was not properly decoded, then
                // prevent it from being pushed to backend.
                val service = model.data_.service
                if (checkIfStub && !service.canEdit()) {
                    return@mapNotNull null
                }
                var new = model
                new = transform(new)
                // If the folder was not changed, then we do not need to
                // update it in the database.
                if (checkIfChanged && new == model) {
                    return@mapNotNull null
                }

                new = new.copy(
                    data_ = new.data_.copy(
                        revisionDate = now,
                    ),
                )
                new
            }
        if (models.isEmpty()) {
            return@modifyDatabase ModifyDatabase.Result.unit()
        }
        dao.transaction {
            models.forEach { folder ->
                dao.insert(
                    folderId = folder.folderId,
                    accountId = folder.accountId,
                    data = folder.data_,
                )
            }
        }

        val changedAccountIds = models
            .map { AccountId(it.accountId) }
            .toSet()
        ModifyDatabase.Result.unit(changedAccountIds)
    }
}
