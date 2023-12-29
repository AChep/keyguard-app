package com.artemchep.keyguard.common.service.power

import com.artemchep.keyguard.common.model.Screen
import kotlinx.coroutines.flow.Flow

interface PowerService {
    fun getScreenState(): Flow<Screen>
}
