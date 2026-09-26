package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.usecase.MarkAllWatchtowerAlertAsRead

/**
 * @author Artem Chepurnyi
 */
class MarkAllWatchtowerAlertAsReadImpl(
    private val databaseManager: VaultDatabaseManager,
) : MarkAllWatchtowerAlertAsRead {
    override fun invoke(
    ): IO<Unit> = databaseManager.get()
        .effectMap { db ->
            db.watchtowerThreatQueries
                .markAllAsRead()
        }

}
