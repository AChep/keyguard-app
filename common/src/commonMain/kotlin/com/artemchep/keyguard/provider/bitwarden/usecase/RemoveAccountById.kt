package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.parallel
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.AccountTask
import com.artemchep.keyguard.common.usecase.RemoveAccountById
import com.artemchep.keyguard.common.usecase.Watchdog
import com.artemchep.keyguard.common.usecase.unit
import com.artemchep.keyguard.core.store.DatabaseManager
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class RemoveAccountByIdImpl(
    private val db: DatabaseManager,
    private val watchdog: Watchdog,
) : RemoveAccountById {
    companion object {
        private const val TAG = "RemoveAccountById.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        db = directDI.instance(),
        watchdog = directDI.instance(),
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
        .mutate(TAG) { database ->
            val dao = database.accountQueries
            dao.deleteByAccountId(accountId.id)
        }
}
