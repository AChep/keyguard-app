package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutThemeExpressive
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutThemeExpressiveImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutThemeExpressive {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(expressive: Boolean): IO<Unit> = settingsReadWriteRepository
        .setThemeM3Expressive(expressive)
}
