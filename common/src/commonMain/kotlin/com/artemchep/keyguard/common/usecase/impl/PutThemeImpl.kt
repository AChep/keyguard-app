package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AppTheme
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutTheme
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutThemeImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutTheme {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(theme: AppTheme?): IO<Unit> = settingsReadWriteRepository
        .setTheme(theme)
}
