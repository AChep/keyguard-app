package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetBrowserAutofillAgentPairingCode
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetBrowserAutofillAgentPairingCodeImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetBrowserAutofillAgentPairingCode {
    private val sharedFlow = settingsReadRepository.getBrowserAutofillAgentPairingCode()
        .distinctUntilChanged()

    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke() = sharedFlow
}
