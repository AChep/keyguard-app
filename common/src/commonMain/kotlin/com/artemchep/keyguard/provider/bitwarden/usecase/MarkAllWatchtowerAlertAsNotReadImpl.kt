package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.usecase.MarkAllWatchtowerAlertAsNotRead
import com.artemchep.keyguard.core.store.DatabaseManager
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class MarkAllWatchtowerAlertAsNotReadImpl(
    private val databaseManager: DatabaseManager,
) : MarkAllWatchtowerAlertAsNotRead {
    constructor(directDI: DirectDI) : this(
        databaseManager = directDI.instance(),
    )

    override fun invoke(
    ): IO<Unit> = databaseManager.get()
        .effectMap { db ->
            db.watchtowerThreatQueries
                .markAllAsNotRead()
        }

}
