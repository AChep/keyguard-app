package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetSshAgent
import kotlinx.coroutines.flow.distinctUntilChanged

class GetSshAgentImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetSshAgent {
    private val sharedFlow = settingsReadRepository.getSshAgent()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
