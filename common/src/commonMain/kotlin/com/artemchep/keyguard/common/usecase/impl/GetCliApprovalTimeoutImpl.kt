package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetCliApprovalTimeout

class GetCliApprovalTimeoutImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetCliApprovalTimeout {
    private val sharedFlow = settingsReadRepository.getCliApprovalTimeout()

    override fun invoke() = sharedFlow
}
