package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetKeepScreenOn
import kotlinx.coroutines.flow.distinctUntilChanged

class GetKeepScreenOnImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetKeepScreenOn {
    private val sharedFlow = settingsReadRepository.getKeepScreenOn()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
