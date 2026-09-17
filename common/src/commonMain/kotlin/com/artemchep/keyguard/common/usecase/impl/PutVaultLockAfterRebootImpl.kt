package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutVaultLockAfterReboot

class PutVaultLockAfterRebootImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutVaultLockAfterReboot {
    override fun invoke(enabled: Boolean): IO<Unit> = settingsReadWriteRepository
        .setVaultLockAfterReboot(enabled)
}
