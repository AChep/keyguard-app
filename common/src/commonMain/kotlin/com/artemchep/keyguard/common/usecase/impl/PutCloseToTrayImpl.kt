package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutCloseToTray
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutCloseToTrayImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutCloseToTray {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(closeToTray: Boolean): IO<Unit> = settingsReadWriteRepository
        .setCloseToTray(closeToTray)
}
