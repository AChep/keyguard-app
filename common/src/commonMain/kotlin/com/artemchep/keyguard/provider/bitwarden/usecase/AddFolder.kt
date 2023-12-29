package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.usecase.AddFolder
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyDatabase
import kotlinx.datetime.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance

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

    constructor(directDI: DirectDI) : this(
        modifyDatabase = directDI.instance(),
        cryptoGenerator = directDI.instance(),
    )

    override fun invoke(
        accountIdsToNames: Map<AccountId, String>,
    ): IO<Set<String>> = modifyDatabase { database ->
        val dao = database.folderQueries
        val now = Clock.System.now()

        val models = accountIdsToNames
            .map { (accountId, name) ->
                val folderId = cryptoGenerator.uuid()
                BitwardenFolder(
                    accountId = accountId.id,
                    folderId = folderId,
                    revisionDate = now,
                    name = name,
                    service = BitwardenService(
                        version = BitwardenService.VERSION,
                    ),
                )
            }
        if (models.isEmpty()) {
            return@modifyDatabase ModifyDatabase.Result(
                changedAccountIds = emptySet(),
                value = emptySet(),
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
            // pass the set of folder ids back
            value = models
                .map { it.folderId }
                .toSet(),
        )
    }
}
