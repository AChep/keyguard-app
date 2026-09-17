package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetDebugPremium
import kotlinx.coroutines.flow.distinctUntilChanged

class GetDebugPremiumImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetDebugPremium {
    private val sharedFlow = settingsReadRepository.getDebugPremium()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
