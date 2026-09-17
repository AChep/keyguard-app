package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetSshAgentDisplayKeyNames

class GetSshAgentDisplayKeyNamesImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetSshAgentDisplayKeyNames {
    private val sharedFlow = settingsReadRepository.getSshAgentDisplayKeyNames()

    override fun invoke() = sharedFlow
}
