package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetGpgAgentDisplayKeyNames
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetGpgAgentDisplayKeyNamesImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetGpgAgentDisplayKeyNames {
    private val sharedFlow = settingsReadRepository.getGpgAgentDisplayKeyNames()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
