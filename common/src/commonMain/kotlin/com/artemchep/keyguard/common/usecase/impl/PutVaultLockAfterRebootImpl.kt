package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutVaultLockAfterReboot
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutVaultLockAfterRebootImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutVaultLockAfterReboot {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(enabled: Boolean): IO<Unit> = settingsReadWriteRepository
        .setVaultLockAfterReboot(enabled)
}
