package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetEnvironmentsMcpApprovalWindow

class GetEnvironmentsMcpApprovalWindowImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetEnvironmentsMcpApprovalWindow {
    private val sharedFlow = settingsReadRepository.getEnvironmentsMcpApprovalWindow()

    override fun invoke() = sharedFlow
}
