package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverConfig
import kotlinx.coroutines.flow.distinctUntilChanged

class GetGpgKeyserverConfigImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetGpgKeyserverConfig {
    private val sharedFlow = settingsReadRepository.getGpgKeyserverConfig()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
