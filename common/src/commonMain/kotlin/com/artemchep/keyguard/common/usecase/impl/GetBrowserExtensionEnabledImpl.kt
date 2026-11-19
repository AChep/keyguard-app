package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetBrowserExtensionEnabled
import kotlinx.coroutines.flow.distinctUntilChanged

class GetBrowserExtensionEnabledImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetBrowserExtensionEnabled {
    private val sharedFlow = settingsReadRepository.getBrowserExtensionEnabled()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
