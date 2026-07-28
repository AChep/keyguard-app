package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetPersistedNavItemsConfig
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetPersistedNavItemsConfigImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetPersistedNavItemsConfig {
    private val sharedFlow = settingsReadRepository.getNavItemsConfig()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
