package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAppUpdateLevel
import kotlinx.coroutines.flow.distinctUntilChanged

class GetAppUpdateLevelImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetAppUpdateLevel {
    private val sharedFlow = settingsReadRepository.getAppUpdateLevel()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
