package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutAllowScreenshots
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutAllowScreenshotsImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutAllowScreenshots {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(concealFields: Boolean): IO<Unit> = settingsReadWriteRepository
        .setAllowScreenshots(concealFields)
}
