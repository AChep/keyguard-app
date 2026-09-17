package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindow
import kotlinx.coroutines.flow.distinctUntilChanged

class GetGpgAgentApprovalWindowImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetGpgAgentApprovalWindow {
    private val sharedFlow = settingsReadRepository.getGpgAgentApprovalWindow()
        .distinctUntilChanged()

    override fun invoke() = sharedFlow
}
