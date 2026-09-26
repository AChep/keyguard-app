package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import kotlinx.coroutines.flow.distinctUntilChanged

class GetGpgAgentFilterImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetGpgAgentFilter {
    private val sharedFlow = settingsReadRepository.getGpgAgentFilter()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
