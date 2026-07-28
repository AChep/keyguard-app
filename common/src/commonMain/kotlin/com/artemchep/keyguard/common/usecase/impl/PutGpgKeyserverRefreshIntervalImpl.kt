package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutGpgKeyserverRefreshInterval
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Duration

class PutGpgKeyserverRefreshIntervalImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutGpgKeyserverRefreshInterval {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(duration: Duration) = settingsReadWriteRepository
        .setGpgKeyserverRefreshInterval(duration)
}
