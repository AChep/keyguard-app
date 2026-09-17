package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AppTheme
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutTheme

class PutThemeImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutTheme {
    override fun invoke(theme: AppTheme?): IO<Unit> = settingsReadWriteRepository
        .setTheme(theme)
}
