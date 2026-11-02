package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetUiScale

class GetUiScaleImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetUiScale {
    private val sharedFlow = settingsReadRepository.getUiScale()

    override fun invoke() = sharedFlow
}
