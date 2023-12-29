package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.model.Screen
import com.artemchep.keyguard.common.service.power.PowerService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

class PowerServiceJvm(
) : PowerService {
    constructor(
        directDI: DirectDI,
    ) : this(
    )

    override fun getScreenState(): Flow<Screen> = flowOf(Screen.On)
}
