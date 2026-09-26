package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetOnboardingLastVisitInstant
import kotlinx.coroutines.flow.distinctUntilChanged

class GetOnboardingLastVisitInstantImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetOnboardingLastVisitInstant {
    private val sharedFlow = settingsReadRepository.getOnboardingLastVisitInstant()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
