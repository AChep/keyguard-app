package com.artemchep.keyguard.provider.bitwarden.usecase

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.CipherId
import com.artemchep.keyguard.common.model.DWatchtowerAlert
import com.artemchep.keyguard.common.model.DWatchtowerAlertType
import com.artemchep.keyguard.common.usecase.GetWatchtowerAlerts
import com.artemchep.keyguard.core.store.DatabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flattenConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class GetWatchtowerAlertsImpl(
    private val databaseManager: DatabaseManager,
) : GetWatchtowerAlerts {
    constructor(directDI: DirectDI) : this(
        databaseManager = directDI.instance(),
    )

    private val sharedFlow = databaseManager.get()
        .effectMap {
            it.watchtowerThreatQueries
                .getThreats()
                .asFlow()
                .mapToList(Dispatchers.Default)
                .map { list ->
                    list
                        .mapNotNull { item ->
                            val type = DWatchtowerAlertType.of(item.type)
                                ?: return@mapNotNull null
                            val cipherId = CipherId(item.cipherId)
                            DWatchtowerAlert(
                                alertId = item.cipherId + "|" + item.type,
                                cipherId = cipherId,
                                type = type,
                                reportedAt = item.reportedAt,
                                read = item.read,
                                version = item.version,
                            )
                        }
                }
        }
        .asFlow()
        .flattenConcat()
        .shareIn(
            scope = GlobalScope,
            started = SharingStarted.WhileSubscribed(5000),
            replay = 1,
        )

    override fun invoke(): Flow<List<DWatchtowerAlert>> = sharedFlow
}
