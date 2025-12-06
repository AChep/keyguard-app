package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.usecase.RemoveAccounts
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class RemoveAccountsImpl(
    private val db: VaultDatabaseManager,
) : RemoveAccounts {
    companion object {
        private const val TAG = "RemoveAccounts.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        db = directDI.instance(),
    )

    override fun invoke(): IO<Unit> = db
        .mutate(TAG) { database ->
            val dao = database.accountQueries
            dao.deleteAll()
        }
}
