package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetDebugPremium
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetDebugPremiumImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetDebugPremium {
    private val sharedFlow = settingsReadRepository.getDebugPremium()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
