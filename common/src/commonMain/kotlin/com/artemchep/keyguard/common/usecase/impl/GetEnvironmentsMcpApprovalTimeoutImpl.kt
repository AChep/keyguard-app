package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetEnvironmentsMcpApprovalTimeout

class GetEnvironmentsMcpApprovalTimeoutImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetEnvironmentsMcpApprovalTimeout {
    private val sharedFlow = settingsReadRepository.getEnvironmentsMcpApprovalTimeout()

    override fun invoke() = sharedFlow
}
