package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetGpgAgentDisplayKeyNames
import kotlinx.coroutines.flow.distinctUntilChanged

class GetGpgAgentDisplayKeyNamesImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetGpgAgentDisplayKeyNames {
    private val sharedFlow = settingsReadRepository.getGpgAgentDisplayKeyNames()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
