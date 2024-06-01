package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.CipherId
import com.artemchep.keyguard.common.usecase.MarkWatchtowerAlertAsRead
import com.artemchep.keyguard.core.store.DatabaseManager
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class MarkWatchtowerAlertAsReadImpl(
    private val databaseManager: DatabaseManager,
) : MarkWatchtowerAlertAsRead {
    constructor(directDI: DirectDI) : this(
        databaseManager = directDI.instance(),
    )

    override fun invoke(
        cipherId: CipherId,
    ): IO<Unit> = databaseManager.get()
        .effectMap { db ->
            db.watchtowerThreatQueries
                .markAsRead(cipherId = cipherId.id)
        }

}
