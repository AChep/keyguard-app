package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutVaultPersist
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutVaultPersistImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutVaultPersist {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(enable: Boolean): IO<Unit> = settingsReadWriteRepository
        .setVaultPersist(enable)
}
