package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.usecase.MarkAllWatchtowerAlertAsNotRead

/**
 * @author Artem Chepurnyi
 */
class MarkAllWatchtowerAlertAsNotReadImpl(
    private val databaseManager: VaultDatabaseManager,
) : MarkAllWatchtowerAlertAsNotRead {
    override fun invoke(
    ): IO<Unit> = databaseManager.get()
        .effectMap { db ->
            db.watchtowerThreatQueries
                .markAllAsNotRead()
        }

}
