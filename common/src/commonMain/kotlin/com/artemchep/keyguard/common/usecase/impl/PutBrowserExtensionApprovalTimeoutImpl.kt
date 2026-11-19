package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutBrowserExtensionApprovalTimeout
import kotlin.time.Duration

class PutBrowserExtensionApprovalTimeoutImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutBrowserExtensionApprovalTimeout {
    override fun invoke(duration: Duration) = settingsReadWriteRepository
        .setBrowserExtensionApprovalTimeout(duration)
}
