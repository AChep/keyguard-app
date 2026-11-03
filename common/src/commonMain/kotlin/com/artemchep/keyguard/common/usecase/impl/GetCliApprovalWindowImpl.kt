package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetCliApprovalWindow

class GetCliApprovalWindowImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetCliApprovalWindow {
    private val sharedFlow = settingsReadRepository.getCliApprovalWindow()

    override fun invoke() = sharedFlow
}
