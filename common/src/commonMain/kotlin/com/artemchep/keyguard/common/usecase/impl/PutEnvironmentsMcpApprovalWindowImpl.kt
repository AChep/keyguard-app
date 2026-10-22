package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutEnvironmentsMcpApprovalWindow
import kotlin.time.Duration

class PutEnvironmentsMcpApprovalWindowImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutEnvironmentsMcpApprovalWindow {
    override fun invoke(duration: Duration) = settingsReadWriteRepository
        .setEnvironmentsMcpApprovalWindow(duration)
}
