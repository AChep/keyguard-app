package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetUseExternalBrowser
import kotlinx.coroutines.flow.distinctUntilChanged

class GetUseExternalBrowserImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetUseExternalBrowser {
    private val sharedFlow = settingsReadRepository.getUseExternalBrowser()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
