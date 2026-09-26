package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutThemeExpressive

class PutThemeExpressiveImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutThemeExpressive {
    override fun invoke(expressive: Boolean): IO<Unit> = settingsReadWriteRepository
        .setThemeM3Expressive(expressive)
}
