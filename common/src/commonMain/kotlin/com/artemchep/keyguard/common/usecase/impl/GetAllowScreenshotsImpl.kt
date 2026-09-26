package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAllowScreenshots
import kotlinx.coroutines.flow.distinctUntilChanged

class GetAllowScreenshotsImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetAllowScreenshots {
    private val sharedFlow = settingsReadRepository.getAllowScreenshots()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
