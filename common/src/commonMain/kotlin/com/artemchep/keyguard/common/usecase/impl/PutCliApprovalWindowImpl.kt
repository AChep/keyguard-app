package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutCliApprovalWindow
import kotlin.time.Duration

class PutCliApprovalWindowImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutCliApprovalWindow {
    override fun invoke(duration: Duration) = settingsReadWriteRepository
        .setCliApprovalWindow(duration)
}
