package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.NavItemsConfig
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutNavItemsConfig
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutNavItemsConfigImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutNavItemsConfig {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(config: NavItemsConfig?): IO<Unit> = settingsReadWriteRepository
        .setNavItemsConfig(config)
}
