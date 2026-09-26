package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutVaultPersist

class PutVaultPersistImpl(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutVaultPersist {
    override fun invoke(enable: Boolean): IO<Unit> = settingsReadWriteRepository
        .setVaultPersist(enable)
}
