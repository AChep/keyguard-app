package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.NavItemsConfig
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutNavItemsConfig

class PutNavItemsConfigImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutNavItemsConfig {
    override fun invoke(config: NavItemsConfig?): IO<Unit> = settingsReadWriteRepository
        .setNavItemsConfig(config)
}
