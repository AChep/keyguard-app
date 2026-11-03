package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalTimeout

class GetGpgAgentApprovalTimeoutImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetGpgAgentApprovalTimeout {
    private val sharedFlow = settingsReadRepository.getGpgAgentApprovalTimeout()

    override fun invoke() = sharedFlow
}
