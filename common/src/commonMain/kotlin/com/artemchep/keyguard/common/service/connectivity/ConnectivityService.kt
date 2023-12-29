package com.artemchep.keyguard.common.service.connectivity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

interface ConnectivityService {
    val availableFlow: Flow<Unit>

    suspend fun awaitAvailable(): Unit = availableFlow.first()

    /**
     * Returns `true` if the device has active internet connection,
     * `false` otherwise.
     */
    fun isInternetAvailable(): Boolean
}
