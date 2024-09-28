package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetWatchtowerUnreadAlerts
import com.artemchep.keyguard.common.usecase.GetWatchtowerUnreadCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class GetWatchtowerUnreadCountImpl(
    private val getWatchtowerUnreadAlerts: GetWatchtowerUnreadAlerts,
    private val getProfiles: GetProfiles,
) : GetWatchtowerUnreadCount {
    constructor(directDI: DirectDI) : this(
        getWatchtowerUnreadAlerts = directDI.instance(),
        getProfiles = directDI.instance(),
    )

    private val sharedFlow = combine(
        getProfiles()
            .map { profiles ->
                profiles
                    .associateBy { it.accountId }
            },
        getWatchtowerUnreadAlerts(),
    ) { profiles, unreadAlerts ->
        unreadAlerts
            .count { alert ->
                val profile = profiles[alert.accountId.id]
                profile?.hidden == false
            }
    }

    override fun invoke(): Flow<Int> = sharedFlow

}
