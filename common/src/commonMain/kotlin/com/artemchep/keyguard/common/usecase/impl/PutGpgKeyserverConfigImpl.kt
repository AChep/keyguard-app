package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutGpgKeyserverConfig

class PutGpgKeyserverConfigImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutGpgKeyserverConfig {
    override fun invoke(config: GpgKeyserverConfig): IO<Unit> = settingsReadWriteRepository
        .setGpgKeyserverConfig(config)
}
