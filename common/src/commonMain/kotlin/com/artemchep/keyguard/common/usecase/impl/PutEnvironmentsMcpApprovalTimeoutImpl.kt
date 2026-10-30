package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutEnvironmentsMcpApprovalTimeout
import kotlin.time.Duration

class PutEnvironmentsMcpApprovalTimeoutImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutEnvironmentsMcpApprovalTimeout {
    override fun invoke(duration: Duration) = settingsReadWriteRepository
        .setEnvironmentsMcpApprovalTimeout(duration)
}
