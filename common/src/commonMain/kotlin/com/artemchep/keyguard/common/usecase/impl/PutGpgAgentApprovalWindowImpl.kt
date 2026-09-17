package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutGpgAgentApprovalWindow
import kotlin.time.Duration

class PutGpgAgentApprovalWindowImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutGpgAgentApprovalWindow {
    override fun invoke(duration: Duration) = settingsReadWriteRepository
        .setGpgAgentApprovalWindow(duration)
}
