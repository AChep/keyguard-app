package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverLastRefresh
import kotlinx.coroutines.flow.distinctUntilChanged

class GetGpgKeyserverLastRefreshImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetGpgKeyserverLastRefresh {
    private val sharedFlow = settingsReadRepository.getGpgKeyserverLastRefresh()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
