package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.CipherId
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.usecase.MarkWatchtowerAlertsAsRead

/**
 * @author Artem Chepurnyi
 */
class MarkWatchtowerAlertsAsReadImpl(
    private val databaseManager: VaultDatabaseManager,
) : MarkWatchtowerAlertsAsRead {
    private companion object {
        // Stay below SQLite's traditional 999 bind-parameter limit.
        const val CIPHER_IDS_BATCH_SIZE = 900
    }

    override fun invoke(
        cipherIds: Set<CipherId>,
    ): IO<Unit> = databaseManager.get()
        .effectMap { db ->
            if (cipherIds.isEmpty()) return@effectMap

            val queries = db.watchtowerThreatQueries
            queries.transaction {
                cipherIds.chunked(CIPHER_IDS_BATCH_SIZE).forEach { batch ->
                    queries.markAsReadByCipherIds(
                        cipherIds = batch.map { it.id },
                    )
                }
            }
        }
}
