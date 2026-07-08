package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutGpgKeyserverConfig
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutGpgKeyserverConfigImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutGpgKeyserverConfig {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(config: GpgKeyserverConfig): IO<Unit> = settingsReadWriteRepository
        .setGpgKeyserverConfig(config)
}
