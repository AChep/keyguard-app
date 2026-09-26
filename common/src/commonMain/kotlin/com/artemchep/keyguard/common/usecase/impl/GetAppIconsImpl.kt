package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAppIcons
import kotlinx.coroutines.flow.distinctUntilChanged

class GetAppIconsImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetAppIcons {
    private val sharedFlow = settingsReadRepository.getAppIcons()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
