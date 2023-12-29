package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutVaultLockAfterScreenOff
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutVaultLockAfterScreenOffImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutVaultLockAfterScreenOff {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(screenLock: Boolean): IO<Unit> = settingsReadWriteRepository
        .setVaultScreenLock(screenLock)
}
