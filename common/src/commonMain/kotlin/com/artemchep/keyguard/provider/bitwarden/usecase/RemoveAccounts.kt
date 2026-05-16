package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.usecase.RemoveAccounts
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class RemoveAccountsImpl(
    private val db: VaultDatabaseManager,
    private val fileService: FileService,
) : RemoveAccounts {
    companion object {
        private const val TAG = "RemoveAccounts.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        db = directDI.instance(),
        fileService = directDI.instance(),
    )

    override fun invoke(): IO<Unit> = db
        .get()
        .effectMap(Dispatchers.IO) { database ->
            cleanupManagedKeePassFiles(
                fileService = fileService,
                tokens = database.accountQueries
                    .get()
                    .executeAsList()
                    .map { it.data_ },
            )
        }
        .effectMap {
            db.mutate(TAG) { database ->
                val dao = database.accountQueries
                dao.deleteAll()
            }.bind()
        }
}
