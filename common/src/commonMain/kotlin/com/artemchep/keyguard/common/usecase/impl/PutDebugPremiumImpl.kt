package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutDebugPremium
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutDebugPremiumImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutDebugPremium {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(premium: Boolean): IO<Unit> = settingsReadWriteRepository
        .setDebugPremium(premium)
}
