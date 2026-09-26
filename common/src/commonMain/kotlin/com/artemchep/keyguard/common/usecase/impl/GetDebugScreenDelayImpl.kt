package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetDebugScreenDelay
import kotlinx.coroutines.flow.distinctUntilChanged

class GetDebugScreenDelayImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetDebugScreenDelay {
    private val sharedFlow = settingsReadRepository.getDebugScreenDelay()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
