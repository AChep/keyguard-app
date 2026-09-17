package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow

class GetSshAgentApprovalWindowImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetSshAgentApprovalWindow {
    private val sharedFlow = settingsReadRepository.getSshAgentApprovalWindow()

    override fun invoke() = sharedFlow
}
