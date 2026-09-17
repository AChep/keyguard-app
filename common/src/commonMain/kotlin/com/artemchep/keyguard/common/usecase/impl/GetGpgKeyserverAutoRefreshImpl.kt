package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverAutoRefresh
import kotlinx.coroutines.flow.distinctUntilChanged

class GetGpgKeyserverAutoRefreshImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetGpgKeyserverAutoRefresh {
    private val sharedFlow = settingsReadRepository.getGpgKeyserverAutoRefresh()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
