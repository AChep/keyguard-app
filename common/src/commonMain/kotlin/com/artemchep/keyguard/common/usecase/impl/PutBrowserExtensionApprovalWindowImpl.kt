package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutBrowserExtensionApprovalWindow
import kotlin.time.Duration

class PutBrowserExtensionApprovalWindowImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutBrowserExtensionApprovalWindow {
    override fun invoke(duration: Duration) = settingsReadWriteRepository
        .setBrowserExtensionApprovalWindow(duration)
}
