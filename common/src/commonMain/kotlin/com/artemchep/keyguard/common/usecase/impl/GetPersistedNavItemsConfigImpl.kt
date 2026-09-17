package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetPersistedNavItemsConfig

class GetPersistedNavItemsConfigImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetPersistedNavItemsConfig {
    private val sharedFlow = settingsReadRepository.getNavItemsConfig()

    override fun invoke() = sharedFlow
}
