package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutGpgAgentApprovalTimeout
import kotlin.time.Duration

class PutGpgAgentApprovalTimeoutImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutGpgAgentApprovalTimeout {
    override fun invoke(duration: Duration) = settingsReadWriteRepository
        .setGpgAgentApprovalTimeout(duration)
}
