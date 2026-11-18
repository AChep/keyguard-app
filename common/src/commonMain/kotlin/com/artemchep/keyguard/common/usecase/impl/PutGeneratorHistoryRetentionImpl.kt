package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutGeneratorHistoryRetention
import kotlin.time.Duration

class PutGeneratorHistoryRetentionImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutGeneratorHistoryRetention {
    override fun invoke(duration: Duration) = settingsReadWriteRepository
        .setGeneratorHistoryRetention(duration)
}
