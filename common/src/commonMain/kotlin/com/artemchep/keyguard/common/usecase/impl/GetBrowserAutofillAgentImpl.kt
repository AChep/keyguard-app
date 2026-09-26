package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetBrowserAutofillAgent
import kotlinx.coroutines.flow.distinctUntilChanged

class GetBrowserAutofillAgentImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetBrowserAutofillAgent {
    private val sharedFlow = settingsReadRepository.getBrowserAutofillAgent()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
