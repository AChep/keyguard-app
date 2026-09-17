package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.connectivity.ConnectivityService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class ConnectivityServiceJvm : ConnectivityService {

    override val availableFlow: Flow<Unit> = flowOf(Unit)

    override fun isInternetAvailable(): Boolean = true
}
