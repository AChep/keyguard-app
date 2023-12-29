package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetOnboardingLastVisitInstant
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetOnboardingLastVisitInstantImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetOnboardingLastVisitInstant {
    private val sharedFlow = settingsReadRepository.getOnboardingLastVisitInstant()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
