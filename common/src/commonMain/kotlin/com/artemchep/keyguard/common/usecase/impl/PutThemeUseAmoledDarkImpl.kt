package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutThemeUseAmoledDark
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutThemeUseAmoledDarkImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutThemeUseAmoledDark {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(useAmoledDark: Boolean): IO<Unit> = settingsReadWriteRepository
        .setThemeUseAmoledDark(useAmoledDark)
}
