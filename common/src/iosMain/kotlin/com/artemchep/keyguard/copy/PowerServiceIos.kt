package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.model.Screen
import com.artemchep.keyguard.common.service.power.PowerService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

object PowerServiceIos : PowerService {
    override fun getScreenState(): Flow<Screen> = flowOf(Screen.On)
}
