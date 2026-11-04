package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutAgentHistoryRetention
import kotlin.time.Duration

class PutAgentHistoryRetentionImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutAgentHistoryRetention {
    override fun invoke(duration: Duration) = settingsReadWriteRepository
        .setAgentHistoryRetention(duration)
}
