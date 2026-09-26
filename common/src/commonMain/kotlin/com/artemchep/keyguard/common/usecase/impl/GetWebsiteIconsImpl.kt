package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import kotlinx.coroutines.flow.distinctUntilChanged

class GetWebsiteIconsImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetWebsiteIcons {
    private val sharedFlow = settingsReadRepository.getWebsiteIcons()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
