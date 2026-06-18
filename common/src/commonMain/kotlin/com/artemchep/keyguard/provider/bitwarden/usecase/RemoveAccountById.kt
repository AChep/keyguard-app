package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.parallel
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.AccountTask
import com.artemchep.keyguard.common.usecase.MarkBackupAsDirty
import com.artemchep.keyguard.common.usecase.RemoveAccountById
import com.artemchep.keyguard.common.usecase.Watchdog
import com.artemchep.keyguard.common.usecase.unit
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class RemoveAccountByIdImpl(
    private val db: VaultDatabaseManager,
    private val fileService: FileService,
    private val watchdog: Watchdog,
    private val markBackupAsDirty: MarkBackupAsDirty,
) : RemoveAccountById {
    companion object {
        private const val TAG = "RemoveAccountById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        db = directDI.instance(),
        fileService = directDI.instance(),
        watchdog = directDI.instance(),
        markBackupAsDirty = directDI.instance(),
    )

    override fun invoke(
        accountIds: Set<AccountId>,
    ): IO<Unit> = accountIds
        .map { accountId ->
            watchdog
                .track(
                    accountId = accountId,
                    accountTask = AccountTask.REMOVE,
                    io = performRemoveAccount(
                        accountId = accountId,
                    ),
                )
        }
        .parallel()
        .unit()

    private fun performRemoveAccount(
        accountId: AccountId,
    ) = db
        .get()
        .effectMap(Dispatchers.IO) { database ->
            val token = database.accountQueries
                .getByAccountId(accountId = accountId.id)
                .executeAsOneOrNull()
                ?.data_
            cleanupManagedKeePassFiles(
                fileService = fileService,
                tokens = listOfNotNull(token),
            )
        }
        .effectMap {
            db.mutate(TAG) { database ->
                val dao = database.accountQueries
                dao.deleteByAccountId(accountId.id)
            }.bind()
            markBackupAsDirty()
                .bind()
        }
}
