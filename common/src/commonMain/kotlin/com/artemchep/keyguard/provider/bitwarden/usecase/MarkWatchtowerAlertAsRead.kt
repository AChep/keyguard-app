package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.CipherId
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.usecase.MarkWatchtowerAlertAsRead

/**
 * @author Artem Chepurnyi
 */
class MarkWatchtowerAlertAsReadImpl(
    private val databaseManager: VaultDatabaseManager,
) : MarkWatchtowerAlertAsRead {
    override fun invoke(
        cipherId: CipherId,
    ): IO<Unit> = databaseManager.get()
        .effectMap { db ->
            db.watchtowerThreatQueries
                .markAsRead(cipherId = cipherId.id)
        }

}
