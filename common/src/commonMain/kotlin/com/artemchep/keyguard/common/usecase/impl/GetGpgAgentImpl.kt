package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetGpgAgent
import kotlinx.coroutines.flow.distinctUntilChanged

class GetGpgAgentImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetGpgAgent {
    private val sharedFlow = settingsReadRepository.getGpgAgent()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
