package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutGpgKeyserverRefreshInterval
import kotlin.time.Duration

class PutGpgKeyserverRefreshIntervalImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutGpgKeyserverRefreshInterval {
    override fun invoke(duration: Duration) = settingsReadWriteRepository
        .setGpgKeyserverRefreshInterval(duration)
}
