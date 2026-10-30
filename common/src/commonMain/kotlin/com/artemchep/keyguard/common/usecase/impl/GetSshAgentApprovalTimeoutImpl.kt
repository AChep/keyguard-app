package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalTimeout

class GetSshAgentApprovalTimeoutImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetSshAgentApprovalTimeout {
    private val sharedFlow = settingsReadRepository.getSshAgentApprovalTimeout()

    override fun invoke() = sharedFlow
}
