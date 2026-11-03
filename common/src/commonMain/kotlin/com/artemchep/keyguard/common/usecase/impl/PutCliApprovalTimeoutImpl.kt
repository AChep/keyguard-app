package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutCliApprovalTimeout
import kotlin.time.Duration

class PutCliApprovalTimeoutImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutCliApprovalTimeout {
    override fun invoke(duration: Duration) = settingsReadWriteRepository
        .setCliApprovalTimeout(duration)
}
