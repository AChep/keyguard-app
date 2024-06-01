package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.usecase.GetWatchtowerUnreadAlerts
import com.artemchep.keyguard.common.usecase.GetWatchtowerUnreadCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class GetWatchtowerUnreadCountImpl(
    private val getWatchtowerUnreadAlerts: GetWatchtowerUnreadAlerts,
) : GetWatchtowerUnreadCount {
    constructor(directDI: DirectDI) : this(
        getWatchtowerUnreadAlerts = directDI.instance(),
    )

    private val sharedFlow = getWatchtowerUnreadAlerts()
        .map { list ->
            list.size
        }

    override fun invoke(): Flow<Int> = sharedFlow

}
