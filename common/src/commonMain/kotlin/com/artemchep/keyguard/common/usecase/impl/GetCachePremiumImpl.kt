package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetCachePremium
import kotlinx.coroutines.flow.distinctUntilChanged

class GetCachePremiumImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetCachePremium {
    private val sharedFlow = settingsReadRepository
        .getCachePremium()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
