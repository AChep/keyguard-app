package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutVaultLockAfterScreenOff

class PutVaultLockAfterScreenOffImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutVaultLockAfterScreenOff {
    override fun invoke(screenLock: Boolean): IO<Unit> = settingsReadWriteRepository
        .setVaultScreenLock(screenLock)
}
