package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import kotlinx.coroutines.flow.distinctUntilChanged

class GetSshAgentFilterImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetSshAgentFilter {
    private val sharedFlow = settingsReadRepository.getSshAgentFilter()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}

