package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutAppIcons
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutAppIconsImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutAppIcons {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(appIcons: Boolean): IO<Unit> = settingsReadWriteRepository
        .setAppIcons(appIcons)
}
