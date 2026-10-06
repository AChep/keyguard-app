package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAutotypeSpeed
import kotlinx.coroutines.flow.distinctUntilChanged

class GetAutotypeSpeedImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetAutotypeSpeed {
    private val sharedFlow = settingsReadRepository.getAutotypeSpeed()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
