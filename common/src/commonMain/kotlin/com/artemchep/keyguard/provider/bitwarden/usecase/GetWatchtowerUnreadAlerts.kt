package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DWatchtowerAlert
import com.artemchep.keyguard.common.usecase.GetWatchtowerAlerts
import com.artemchep.keyguard.common.usecase.GetWatchtowerUnreadAlerts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * @author Artem Chepurnyi
 */
class GetWatchtowerUnreadAlertsImpl(
    private val getWatchtowerAlerts: GetWatchtowerAlerts,
) : GetWatchtowerUnreadAlerts {
    private val sharedFlow = getWatchtowerAlerts()
        .map { alerts ->
            alerts
                .filter { !it.read }
        }

    override fun invoke(): Flow<List<DWatchtowerAlert>> = sharedFlow
}
