package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutSshAgentApprovalWindow
import kotlin.time.Duration

class PutSshAgentApprovalWindowImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutSshAgentApprovalWindow {
    override fun invoke(duration: Duration) = settingsReadWriteRepository
        .setSshAgentApprovalWindow(duration)
}
