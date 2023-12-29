package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutDebugScreenDelay
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutDebugScreenDelayImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutDebugScreenDelay {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(screenDelay: Boolean): IO<Unit> = settingsReadWriteRepository
        .setDebugScreenDelay(screenDelay)
}
