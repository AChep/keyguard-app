package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAppUpdateDelay
import kotlinx.coroutines.flow.distinctUntilChanged

class GetAppUpdateDelayImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetAppUpdateDelay {
    private val sharedFlow = settingsReadRepository.getAppUpdateDelay()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
