package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverRefreshInterval
import kotlinx.coroutines.flow.distinctUntilChanged

class GetGpgKeyserverRefreshIntervalImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetGpgKeyserverRefreshInterval {
    private val sharedFlow = settingsReadRepository.getGpgKeyserverRefreshInterval()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
