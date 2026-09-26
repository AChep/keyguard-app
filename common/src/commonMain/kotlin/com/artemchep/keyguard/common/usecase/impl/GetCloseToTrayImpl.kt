package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetCloseToTray
import kotlinx.coroutines.flow.distinctUntilChanged

class GetCloseToTrayImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetCloseToTray {
    private val sharedFlow = settingsReadRepository.getCloseToTray()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
