package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetBrowserExtensionApprovalTimeout

class GetBrowserExtensionApprovalTimeoutImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetBrowserExtensionApprovalTimeout {
    private val sharedFlow = settingsReadRepository.getBrowserExtensionApprovalTimeout()

    override fun invoke() = sharedFlow
}
