package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetDebugScreenDelay
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetDebugScreenDelayImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetDebugScreenDelay {
    private val sharedFlow = settingsReadRepository.getDebugScreenDelay()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
