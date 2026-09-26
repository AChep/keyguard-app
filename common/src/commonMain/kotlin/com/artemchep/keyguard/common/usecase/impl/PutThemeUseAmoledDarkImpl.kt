package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutThemeUseAmoledDark

class PutThemeUseAmoledDarkImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutThemeUseAmoledDark {
    override fun invoke(useAmoledDark: Boolean): IO<Unit> = settingsReadWriteRepository
        .setThemeUseAmoledDark(useAmoledDark)
}
