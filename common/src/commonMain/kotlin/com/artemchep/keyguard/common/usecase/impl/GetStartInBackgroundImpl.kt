package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetStartInBackground
import kotlinx.coroutines.flow.distinctUntilChanged

class GetStartInBackgroundImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetStartInBackground {
    private val sharedFlow = settingsReadRepository.getStartInBackground()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
