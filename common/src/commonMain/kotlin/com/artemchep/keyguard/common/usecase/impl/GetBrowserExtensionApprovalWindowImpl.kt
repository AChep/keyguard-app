package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetBrowserExtensionApprovalWindow

class GetBrowserExtensionApprovalWindowImpl(
    settingsReadRepository: SettingsReadRepository,
) : GetBrowserExtensionApprovalWindow {
    private val sharedFlow = settingsReadRepository.getBrowserExtensionApprovalWindow()

    override fun invoke() = sharedFlow
}
