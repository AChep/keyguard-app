package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.usecase.MarkBackupAsDirty
import com.artemchep.keyguard.common.usecase.RemoveAccounts
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadGarbageCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class RemoveAccountsImpl(
    private val db: VaultDatabaseManager,
    private val fileService: FileService,
    private val markBackupAsDirty: MarkBackupAsDirty,
    private val pendingUploadGarbageCollector: PendingUploadGarbageCollector,
) : RemoveAccounts {
    companion object {
        private const val TAG = "RemoveAccounts.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        db = directDI.instance(),
        fileService = directDI.instance(),
        markBackupAsDirty = directDI.instance(),
        pendingUploadGarbageCollector = directDI.instance(),
    )

    override fun invoke(): IO<Unit> = db
        .get()
        .effectMap(Dispatchers.IO) { database ->
            val accounts = database.accountQueries
                .get()
                .executeAsList()
            cleanupManagedKeePassFiles(
                fileService = fileService,
                tokens = accounts.map { it.data_ },
            )
            accounts.map { it.accountId }
        }
        .effectMap { accountIds ->
            db.mutate(TAG) { database ->
                val dao = database.accountQueries
                dao.deleteAll()
            }.bind()
            withContext(NonCancellable) {
                accountIds.forEach { accountId ->
                    pendingUploadGarbageCollector
                        .purge(accountId)
                        .bind()
                }
            }
            markBackupAsDirty()
                .bind()
        }
}
