package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutKeepScreenOn
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutKeepScreenOnImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutKeepScreenOn {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(keepScreenOn: Boolean): IO<Unit> = settingsReadWriteRepository
        .setKeepScreenOn(keepScreenOn)
}
