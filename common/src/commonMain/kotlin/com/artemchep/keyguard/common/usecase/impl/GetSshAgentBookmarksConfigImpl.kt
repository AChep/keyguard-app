package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetSshAgentBookmarksConfig

class GetSshAgentBookmarksConfigImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetSshAgentBookmarksConfig {
    private val sharedFlow = settingsReadRepository.getSshAgentBookmarksConfig()

    override fun invoke() = sharedFlow
}
