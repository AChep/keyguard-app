package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutSshAgentApprovalTimeout
import kotlin.time.Duration

class PutSshAgentApprovalTimeoutImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutSshAgentApprovalTimeout {
    override fun invoke(duration: Duration) = settingsReadWriteRepository
        .setSshAgentApprovalTimeout(duration)
}
